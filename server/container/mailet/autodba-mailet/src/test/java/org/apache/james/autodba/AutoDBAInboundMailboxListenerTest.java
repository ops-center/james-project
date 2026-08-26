/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership. The ASF licenses this file    *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License. You may obtain a copy of the License at    *
 *                                                              *
 * http://www.apache.org/licenses/LICENSE-2.0                   *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied. See the License for the     *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.autodba;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.autodba.AutoDBAInboundMailboxListener.Command;
import org.junit.jupiter.api.Test;

class AutoDBAInboundMailboxListenerTest {

    @Test
    void incidentFromPrefersReferencesOverFallback() {
        assertThat(AutoDBAInboundMailboxListener.incidentFrom("<INC-42.abc@autodba> <other@x>", "INC-99"))
            .contains("INC-42");
    }

    @Test
    void incidentFromFallsBackToHeaderWhenReferencesHasNoIncident() {
        assertThat(AutoDBAInboundMailboxListener.incidentFrom("<random@x>", "INC-7")).contains("INC-7");
    }

    @Test
    void incidentFromIsEmptyWhenNeitherSourceHasOne() {
        assertThat(AutoDBAInboundMailboxListener.incidentFrom("<random@x>", "")).isEmpty();
        assertThat(AutoDBAInboundMailboxListener.incidentFrom(null, null)).isEmpty();
    }

    @Test
    void parseRecognisesEachApprovalVerb() {
        for (String verb : new String[] {"approve", "reject", "pause", "resume", "escalate", "comment"}) {
            Command command = AutoDBAInboundMailboxListener.parse(verb + " extra detail");
            assertThat(command.verb()).isEqualTo(verb.toUpperCase(java.util.Locale.ROOT));
            assertThat(command.argument()).isEqualTo("extra detail");
        }
    }

    @Test
    void parseRequiresAnArgumentForRunbookAndAssign() {
        assertThat(AutoDBAInboundMailboxListener.parse("runbook RB-1").verb()).isEqualTo("RUNBOOK");
        assertThat(AutoDBAInboundMailboxListener.parse("assign alice").verb()).isEqualTo("ASSIGN");
    }

    @Test
    void parseDefaultsUnknownVerbsToComment() {
        Command command = AutoDBAInboundMailboxListener.parse("looks fine to me");
        assertThat(command.verb()).isEqualTo("COMMENT");
        assertThat(command.argument()).isEqualTo("looks fine to me");
    }
}
