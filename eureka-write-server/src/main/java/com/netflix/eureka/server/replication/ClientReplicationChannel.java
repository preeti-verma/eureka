/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.eureka.server.replication;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.netflix.eureka.client.transport.ServerConnection;
import com.netflix.eureka.client.transport.TransportClient;
import com.netflix.eureka.interests.ChangeNotification;
import com.netflix.eureka.interests.Interests;
import com.netflix.eureka.protocol.replication.RegisterCopy;
import com.netflix.eureka.protocol.replication.UnregisterCopy;
import com.netflix.eureka.protocol.replication.UpdateCopy;
import com.netflix.eureka.registry.EurekaRegistry;
import com.netflix.eureka.registry.EurekaRegistry.Origin;
import com.netflix.eureka.registry.InstanceInfo;
import com.netflix.eureka.server.service.ReplicationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;
import rx.subjects.ReplaySubject;

/**
 * @author Tomasz Bak
 */
public class ClientReplicationChannel implements ReplicationChannel {

    enum STATE {Idle, Connected, Closed}

    private static final Logger logger = LoggerFactory.getLogger(ClientReplicationChannel.class);

    private static final IllegalStateException CHANNEL_CLOSED_EXCEPTION = new IllegalStateException("Channel is already closed.");

    private final EurekaRegistry<InstanceInfo> registry;
    private final TransportClient transportClient;
    private final long heartbeatIntervalMs;

    private final AtomicReference<STATE> state;
    private final ReplaySubject<Void> lifecycle = ReplaySubject.create();

    /**
     * There can only ever be one connection associated with a channel. This subject provides access to that connection
     * after a call is made to {@link #connect()}
     *
     * Why is this a {@link ReplaySubject}?
     *
     * Since there is always every a single connection created by this channel, everyone needs to get the same
     * connection. Now, the connection creation is lazy (in {@link #connect()} so we need a way to update this
     * {@link Observable}. Hence a {@link rx.subjects.Subject} and one that replays the single connection created.
     */
    private final ReplaySubject<ServerConnection> singleConnectionSubject = ReplaySubject.create();
    private volatile ServerConnection connectionIfConnected;

    private Subscription heartbeatTickSubscription;

    public ClientReplicationChannel(final EurekaRegistry<InstanceInfo> registry, TransportClient transportClient, long heartbeatIntervalMs) {
        this.registry = registry;
        this.transportClient = transportClient;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.state = new AtomicReference<>(STATE.Idle);

        startRegistryReplication();
        startHeartBeating();
    }

    protected void startHeartBeating() {
        heartbeatTickSubscription = Observable.interval(heartbeatIntervalMs, TimeUnit.MILLISECONDS,
                Schedulers.computation())
                .subscribe(new HeartbeatChecker());
    }

    protected void startRegistryReplication() {
        connect().switchMap(new Func1<ServerConnection, Observable<ChangeNotification<InstanceInfo>>>() {
            @Override
            public Observable<ChangeNotification<InstanceInfo>> call(ServerConnection newConnection) {
                return registry.forInterest(Interests.forFullRegistry(), Origin.LOCAL);
            }
        }).subscribe(new Subscriber<ChangeNotification<InstanceInfo>>() {
            @Override
            public void onCompleted() {
                lifecycle.onCompleted();
            }

            @Override
            public void onError(Throwable e) {
                lifecycle.onError(e);
            }

            @Override
            public void onNext(ChangeNotification<InstanceInfo> changeNotification) {
                switch (changeNotification.getKind()) {
                    case Add:
                        subscribeToTransportSend(register(changeNotification.getData()), "register request");
                        break;
                    case Modify:
                        subscribeToTransportSend(update(changeNotification.getData()), "update request");
                        break;
                    case Delete:
                        subscribeToTransportSend(unregister(changeNotification.getData().getId()), "delete request");
                        break;
                }
            }
        });
    }

    @Override
    public void heartbeat() {
        if (connectionIfConnected != null) {
            subscribeToTransportSend(connectionIfConnected.sendHeartbeat(), "heartbeat request");
        }
    }

    @Override
    public void close() {
        if (state.getAndSet(STATE.Closed) == STATE.Closed) {
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Closing client replication channel with state: " + state.get());
        }

        heartbeatTickSubscription.unsubscribe();

        if (null != connectionIfConnected) {
            connectionIfConnected.close();
        }

        lifecycle.onCompleted();
    }

    @Override
    public Observable<Void> asLifecycleObservable() {
        return lifecycle;
    }

    @Override
    public Observable<Void> register(InstanceInfo instanceInfo) {
        if (state.get() == STATE.Closed) {
            return Observable.error(CHANNEL_CLOSED_EXCEPTION);
        }

        return connectionIfConnected.send(new RegisterCopy(instanceInfo));
    }

    @Override
    public Observable<Void> update(InstanceInfo newInfo) {
        if (state.get() == STATE.Closed) {
            return Observable.error(CHANNEL_CLOSED_EXCEPTION);
        }

        return connectionIfConnected.send(new UpdateCopy(newInfo));
    }

    @Override
    public Observable<Void> unregister(String instanceId) {
        if (state.get() == STATE.Closed) {
            return Observable.error(CHANNEL_CLOSED_EXCEPTION);
        }

        return connectionIfConnected.send(new UnregisterCopy(instanceId));
    }

    /**
     * Idempotent method that returns the one and only connection associated with this channel.
     *
     * @return The one and only connection associated with this channel.
     */
    protected Observable<ServerConnection> connect() {
        if (state.compareAndSet(STATE.Idle, STATE.Connected)) {
            return transportClient.connect()
                    .take(1)
                    .map(new Func1<ServerConnection, ServerConnection>() {
                        @Override
                        public ServerConnection call(final ServerConnection serverConnection) {
                            // Guarded by the connection state, so it will only be invoked once.
                            connectionIfConnected = serverConnection;
                            singleConnectionSubject.onNext(serverConnection);
                            singleConnectionSubject.onCompleted();
                            return serverConnection;
                        }
                    });
        } else {
            return singleConnectionSubject;
        }

    }

    protected void subscribeToTransportSend(Observable<Void> sendResult, final String what) {
        sendResult.subscribe(new Action1<Void>() {
            @Override
            public void call(Void aVoid) {
                // Nothing to do for a void.
            }
        }, new Action1<Throwable>() {
            @Override
            public void call(Throwable throwable) {
                logger.warn("Failed to send " + what + " request to the server. Closing the channel.", throwable);
                close();
                lifecycle.onError(throwable);
            }
        });
    }

    private class HeartbeatChecker extends Subscriber<Long> {

        @Override
        public void onCompleted() {
            close();
        }

        @Override
        public void onError(Throwable e) {
            logger.error("Heartbeat checker subscription got an error. This will close this replication channel.", e);
            close();
        }

        @Override
        public void onNext(Long aLong) {
            heartbeat();
        }
    }
}
