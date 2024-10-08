/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.user.api;

import javax.inject.Inject;

import org.apache.james.core.Username;
import org.reactivestreams.Publisher;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class DelegationUsernameChangeTaskStep implements UsernameChangeTaskStep {
    private final DelegationStore delegationStore;

    @Inject
    public DelegationUsernameChangeTaskStep(DelegationStore delegationStore) {
        this.delegationStore = delegationStore;
    }

    @Override
    public StepName name() {
        return new StepName("DelegationUsernameChangeTaskStep");
    }

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public Publisher<Void> changeUsername(Username oldUsername, Username newUsername) {
        return migrateDelegatees(oldUsername, newUsername)
            .then(migrateDelegators(oldUsername, newUsername));
    }

    private Mono<Void> migrateDelegators(Username oldUsername, Username newUsername) {
        return Flux.from(delegationStore.delegatedUsers(oldUsername))
            .concatMap(delegator -> Mono.from(delegationStore.addAuthorizedUser(delegator, newUsername))
                .then(Mono.from(delegationStore.removeAuthorizedUser(delegator, oldUsername))))
            .then();
    }

    private Flux<Void> migrateDelegatees(Username oldUsername, Username newUsername) {
        return Flux.from(delegationStore.authorizedUsers(oldUsername))
            .concatMap(delegatee -> Mono.from(delegationStore.addAuthorizedUser(newUsername, delegatee))
                .then(Mono.from(delegationStore.removeAuthorizedUser(oldUsername, delegatee))));
    }
}
