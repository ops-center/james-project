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

package matchers;

import com.github.steveash.guavate.Guavate;
import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.mailet.Mail;
import org.apache.mailet.base.GenericMatcher;

import java.util.Collection;

import static org.apache.james.TrimSuffixOfPlusSign.trimSuffixOfPlusSign;


public class TrueBlacklist extends GenericMatcher {
    TrueBlacklist() {

    }

    @Override
    public Collection<MailAddress> match(Mail mail) {
        return mail.getRecipients()
            .stream()
            .filter(recipient -> !isDomainAllowed(mail.getMaybeSender(), recipient))
            .collect(Guavate.toImmutableList());
    }

    private Boolean isDomainAllowed(MaybeSender maybeSender, MailAddress recipient) {
        if (!recipient.getDomain().equals(maybeSender.get().getDomain()) || !recipient.getDomain().asString().equals("cloud.appscode.com")){
            return Boolean.TRUE;
        }

        return Boolean.FALSE;
    }
}
