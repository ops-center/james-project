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

package org.apache.james.mailbox.postgres.quota;

import org.apache.james.backends.postgres.PostgresExtension;
import org.apache.james.backends.postgres.quota.PostgresQuotaDataDefinition;
import org.apache.james.backends.postgres.quota.PostgresQuotaLimitDAO;
import org.apache.james.mailbox.quota.MaxQuotaManager;
import org.apache.james.mailbox.quota.QuotaChangeNotifier;
import org.apache.james.mailbox.store.quota.GenericMaxQuotaManagerTest;
import org.junit.jupiter.api.extension.RegisterExtension;

public class PostgresPerUserMaxQuotaManagerTest extends GenericMaxQuotaManagerTest {
    @RegisterExtension
    static PostgresExtension postgresExtension = PostgresExtension.withoutRowLevelSecurity(PostgresQuotaDataDefinition.MODULE);

    @Override
    protected MaxQuotaManager provideMaxQuotaManager() {
        return new PostgresPerUserMaxQuotaManager(new PostgresQuotaLimitDAO(postgresExtension.getDefaultPostgresExecutor()), QuotaChangeNotifier.NOOP);
    }
}
