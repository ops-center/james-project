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

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.mail.MessagingException;

import org.apache.james.mailbox.MailboxManager;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.transport.mailets.delivery.MailDispatcher;
import org.apache.james.transport.mailets.delivery.MailboxAppenderImpl;
import org.apache.james.transport.mailets.delivery.SimpleMailStore;
import org.apache.james.user.api.UsersRepository;
import org.apache.mailet.Mail;
import org.apache.mailet.base.GenericMailet;

/**
 * Delivers mail addressed to the Auto DBA reply account into a configured mailbox
 * folder (default {@code incoming}) instead of INBOX, so
 * {@link AutoDBAInboundMailboxListener} can subscribe to just that folder.
 *
 * <pre>
 * &lt;mailet match="RecipientIs=autodba@example.com" class="AutoDBAInboundDelivery"&gt;
 *    &lt;folder&gt;incoming&lt;/folder&gt;
 * &lt;/mailet&gt;
 * </pre>
 */
public class AutoDBAInboundDelivery extends GenericMailet {
    private static final String DEFAULT_FOLDER = "incoming";

    private final UsersRepository usersRepository;
    private final MailboxManager mailboxManager;
    private final MetricFactory metricFactory;
    private MailDispatcher mailDispatcher;

    @Inject
    public AutoDBAInboundDelivery(UsersRepository usersRepository, @Named("mailboxmanager") MailboxManager mailboxManager,
                                   MetricFactory metricFactory) {
        this.usersRepository = usersRepository;
        this.mailboxManager = mailboxManager;
        this.metricFactory = metricFactory;
    }

    @Override
    public void init() throws MessagingException {
        String folder = getInitParameter("folder", DEFAULT_FOLDER);
        mailDispatcher = MailDispatcher.builder()
            .mailStore(SimpleMailStore.builder()
                .mailboxAppender(new MailboxAppenderImpl(mailboxManager))
                .usersRepository(usersRepository)
                .folder(folder)
                .metric(metricFactory.generate("autoDBAInboundDeliveredMails"))
                .build())
            .consume(getInitParameter("consume", true))
            .onMailetException(getInitParameter("onMailetException", Mail.ERROR))
            .mailetContext(getMailetContext())
            .usersRepository(usersRepository)
            .build();
    }

    @Override
    public void service(Mail mail) throws MessagingException {
        mailDispatcher.dispatch(mail);
    }

    @Override
    public String getMailetInfo() {
        return AutoDBAInboundDelivery.class.getName() + " Mailet";
    }
}
