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

package org.apache.james;

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;

public final class TrimSuffixOfPlusSign {
    public static MailAddress trimSuffixOfPlusSign(MailAddress recipient) throws RuntimeException {
        String localPart = recipient.getLocalPart();
        String domainPart = String.valueOf(recipient.getDomain().asString());

        int firstPlusSign = localPart.length();
        for (int i = 0; i < localPart.length(); i++) {
            if (localPart.charAt(i) == '+') {
                firstPlusSign = i;
                break;
            }
        }

        try {
            return new MailAddress(localPart.substring(0, firstPlusSign), domainPart);
        } catch (AddressException e) {
            throw new RuntimeException(e);
        }
    }
}
