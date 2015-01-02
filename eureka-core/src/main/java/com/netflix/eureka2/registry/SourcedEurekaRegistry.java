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

package com.netflix.eureka2.registry;

import com.netflix.eureka2.interests.ChangeNotification;
import com.netflix.eureka2.interests.Interest;
import com.netflix.eureka2.registry.instance.Delta;
import rx.Observable;

import java.util.Set;

/**
 * Interface for eureka registries that contain a notion of data source
 *
 * @author Tomasz Bak
 */
public interface SourcedEurekaRegistry<T> extends EurekaRegistry<T> {

    /**
     * @return a boolean to denote whether the register added a new entry
     */
    Observable<Boolean> register(T instanceInfo, Source source);

    /**
     * @return a boolean to denote whether the update added a new entry
     */
    Observable<Boolean> unregister(T instanceInfo, Source source);

    /**
     * @return a boolean to denote whether the unregister removed an existing entry
     */
    Observable<Boolean> update(T updatedInfo, Set<Delta<?>> deltas, Source source);

    Observable<T> forSnapshot(Interest<T> interest, Source source);

    Observable<ChangeNotification<T>> forInterest(Interest<T> interest, Source source);

    /**
     * Evict all registry info for the given source
     * @return an observable of long denoting the number of holder items touched for the eviction
     */
    Observable<Long> evictAll(Source source);

    /**
     * Evict all registry info for all sources
     * @return an observable of long denoting the number of holder items touched for the eviction
     */
    Observable<Long> evictAll();

    Observable<? extends MultiSourcedDataHolder<T>> getHolders();
}