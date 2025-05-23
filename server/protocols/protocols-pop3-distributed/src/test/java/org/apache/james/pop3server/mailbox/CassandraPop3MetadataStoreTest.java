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

package org.apache.james.pop3server.mailbox;

import org.apache.james.backends.cassandra.CassandraClusterExtension;
import org.apache.james.mailbox.cassandra.ids.CassandraId;
import org.apache.james.mailbox.cassandra.ids.CassandraMessageId;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MessageId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

class CassandraPop3MetadataStoreTest implements Pop3MetadataStoreContract {
    @RegisterExtension
    static CassandraClusterExtension cassandra = new CassandraClusterExtension(Pop3MetadataDataDefinition.MODULE);

    CassandraPop3MetadataStore testee;

    @BeforeEach
    void setUp() {
        testee = new CassandraPop3MetadataStore(cassandra.getCassandraCluster().getConf());
    }

    @Override
    public Pop3MetadataStore testee() {
        return testee;
    }

    @Override
    public MailboxId generateMailboxId() {
        return CassandraId.timeBased();
    }

    @Override
    public MessageId generateMessageId() {
        return new CassandraMessageId.Factory().generate();
    }
}