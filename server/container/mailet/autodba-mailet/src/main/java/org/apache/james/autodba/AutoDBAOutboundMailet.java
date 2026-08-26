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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import org.apache.mailet.Mail;
import org.apache.mailet.base.GenericMailet;

/** Adds the incident thread metadata and a signed, immutable diagnostic bundle. */
public class AutoDBAOutboundMailet extends GenericMailet {
    private String secret;

    @Override
    public void init() throws MessagingException {
        secret = required("hmacSecret");
    }

    @Override
    public void service(Mail mail) throws MessagingException {
        MimeMessage message = (MimeMessage) mail.getMessage();
        String incident = required("incident");
        String token = hmac(incident);
        String bundle = bundle(incident);

        message.setHeader("X-AutoDBA-Incident", incident);
        message.setHeader("X-AutoDBA-Token", token);
        message.setHeader("X-AutoDBA-Quorum", value("quorum", "1-of-1"));
        message.setHeader("X-AutoDBA-Recipients-Mode", value("recipientsMode", "1to1"));
        message.setHeader("List-Id", "autodba-" + incident + ".autodba");
        message.setHeader("X-AutoDBA-Attachment-Signature", hmac(bundle));
        appendBundle(message, incident, bundle);
        message.saveChanges();
    }

    private void appendBundle(MimeMessage message, String incident, String bundle) throws MessagingException {
        try {
            Object existing = message.getContent();
            MimeMultipart multipart = new MimeMultipart("mixed");
            MimeBodyPart original = new MimeBodyPart();
            if (existing instanceof Multipart) {
                original.setContent((Multipart) existing);
            } else {
                original.setContent(existing, message.getContentType());
            }
            multipart.addBodyPart(original);

            MimeBodyPart attachment = new MimeBodyPart();
            attachment.setText(bundle, StandardCharsets.UTF_8.name(), "json");
            attachment.setDisposition(Part.ATTACHMENT);
            attachment.setFileName("autodba-" + incident + ".json");
            multipart.addBodyPart(attachment);
            message.setContent(multipart);
        } catch (IOException e) {
            throw new MessagingException("cannot add Auto DBA diagnostic bundle", e);
        }
    }

    private String bundle(String incident) {
        return "{\"incidentId\":\"" + escape(incident) + "\",\"quorum\":\""
            + escape(value("quorum", "1-of-1")) + "\",\"recipientsMode\":\""
            + escape(value("recipientsMode", "1to1")) + "\"}";
    }

    private String required(String name) throws MessagingException {
        String parameter = getInitParameter(name);
        if (parameter == null || parameter.isBlank()) {
            throw new MessagingException(name + " is required");
        }
        return parameter;
    }

    private String value(String name, String fallback) {
        String parameter = getInitParameter(name);
        return parameter == null ? fallback : parameter;
    }

    private String hmac(String input) throws MessagingException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new MessagingException("cannot compute Auto DBA token", e);
        }
    }

    private static String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
