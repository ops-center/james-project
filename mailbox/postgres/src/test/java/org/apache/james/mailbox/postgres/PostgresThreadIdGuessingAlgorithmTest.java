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

package org.apache.james.mailbox.postgres;

import org.apache.james.backends.postgres.PostgresExtension;
import org.apache.james.events.EventBus;
import org.apache.james.events.EventBusTestFixture;
import org.apache.james.events.InVMEventBus;
import org.apache.james.events.MemoryEventDeadLetters;
import org.apache.james.events.delivery.InVmEventDelivery;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.postgres.mail.dao.PostgresThreadDAO;
import org.apache.james.mailbox.store.CombinationManagerTestSystem;
import org.apache.james.mailbox.store.ThreadIdGuessingAlgorithmContract;
import org.apache.james.mailbox.store.mail.ThreadIdGuessingAlgorithm;
import org.apache.james.mailbox.store.quota.NoQuotaManager;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.junit.jupiter.api.extension.RegisterExtension;

public class PostgresThreadIdGuessingAlgorithmTest extends ThreadIdGuessingAlgorithmContract {
    @RegisterExtension
    static PostgresExtension postgresExtension = PostgresExtension.withoutRowLevelSecurity(PostgresMailboxAggregateDataDefinition.MODULE);

    private MessageId.Factory messageIdFactory;

    @Override
    protected CombinationManagerTestSystem createTestingSystem() {
        EventBus eventBus = new InVMEventBus(new InVmEventDelivery(new RecordingMetricFactory()), EventBusTestFixture.RETRY_BACKOFF_CONFIGURATION, new MemoryEventDeadLetters());
        PostgresCombinationManagerTestSystem testSystem = (PostgresCombinationManagerTestSystem) PostgresCombinationManagerTestSystem.createTestingData(postgresExtension, new NoQuotaManager(), eventBus);
        messageIdFactory = new PostgresMessageId.Factory();
        return testSystem;
    }

    @Override
    protected ThreadIdGuessingAlgorithm initThreadIdGuessingAlgorithm(CombinationManagerTestSystem testingData) {
        PostgresThreadDAO.Factory threadDAOFactory = new PostgresThreadDAO.Factory(postgresExtension.getExecutorFactory());
        return new PostgresThreadIdGuessingAlgorithm(threadDAOFactory);
    }

    @Override
    protected MessageId initNewBasedMessageId() {
        return messageIdFactory.generate();
    }

    @Override
    protected MessageId initOtherBasedMessageId() {
        return messageIdFactory.generate();
    }

}
