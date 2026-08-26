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

import java.io.InputStream;
import java.util.Properties;

import jakarta.inject.Inject;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.events.MailboxEvents;
import org.apache.james.mailbox.model.Content;
import org.apache.james.mailbox.model.FetchGroup;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.MessageResult;

/** Reads the reply headers and text body James stored for an {@code Added} event. */
public class MailboxAddedMessageReader implements AutoDBAInboundMailboxListener.AddedMessageReader {
    private static final Session MIME_SESSION = Session.getDefaultInstance(new Properties());

    private final MailboxManager mailboxManager;

    @Inject
    public MailboxAddedMessageReader(MailboxManager mailboxManager) {
        this.mailboxManager = mailboxManager;
    }

    @Override
    public AutoDBAInboundMailboxListener.InboundMessage read(MailboxEvents.Added event) throws Exception {
        MailboxSession session = mailboxManager.createSystemSession(event.getUsername());
        try {
            MessageManager mailbox = mailboxManager.getMailbox(event.getMailboxId(), session);
            MimeMessage mimeMessage = fetch(mailbox, event, session);
            return new AutoDBAInboundMailboxListener.InboundMessage(
                header(mimeMessage, "References"),
                header(mimeMessage, "X-AutoDBA-Incident"),
                header(mimeMessage, "X-AutoDBA-Token"),
                textBody(mimeMessage));
        } finally {
            mailboxManager.endProcessingRequest(session);
        }
    }

    private static MimeMessage fetch(MessageManager mailbox, MailboxEvents.Added event, MailboxSession session) throws Exception {
        var uid = event.getUids().iterator().next();
        MessageResult result = mailbox.getMessages(MessageRange.one(uid), FetchGroup.FULL_CONTENT, session).next();
        return toMimeMessage(result.getFullContent());
    }

    private static MimeMessage toMimeMessage(Content content) throws Exception {
        try (InputStream in = content.getInputStream()) {
            return new MimeMessage(MIME_SESSION, in);
        }
    }

    private static String header(MimeMessage message, String name) throws Exception {
        String[] values = message.getHeader(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    private static String textBody(MimeMessage message) throws Exception {
        Object content = message.getContent();
        if (content instanceof Multipart multipart) {
            return firstTextPart(multipart);
        }
        return content instanceof String text ? text : "";
    }

    private static String firstTextPart(Multipart multipart) throws Exception {
        for (int i = 0; i < multipart.getCount(); i++) {
            Part part = multipart.getBodyPart(i);
            if (part.isMimeType("text/plain")) {
                Object body = part.getContent();
                return body instanceof String text ? text : "";
            }
            if (part.getContent() instanceof Multipart nested) {
                String value = firstTextPart(nested);
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return "";
    }
}
