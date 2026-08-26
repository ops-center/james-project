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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.james.events.Event;
import org.apache.james.events.EventListener;
import org.apache.james.events.Group;
import org.apache.james.mailbox.events.MailboxEvents;

/**
 * A James EventBus listener for replies deposited in {@code incoming/}. The mailbox adapter
 * supplies message headers and body for each {@link MailboxEvents.Added} event.
 */
public class AutoDBAInboundMailboxListener implements EventListener.GroupEventListener {
    private static final Pattern INCIDENT = Pattern.compile("(INC-[A-Za-z0-9_-]+)");
    private static final Group GROUP = new AutoDBAInboundMailboxListenerGroup();

    private final HttpClient httpClient;
    private final URI controller;
    private final String secret;
    private final AddedMessageReader messageReader;

    public AutoDBAInboundMailboxListener(String hmacSecret, AddedMessageReader messageReader) {
        this(hmacSecret, messageReader, HttpClient.newHttpClient(),
            URI.create("http://autodba-controller:8080/autodba.v1.ControllerService/SubmitCommand"));
    }

    AutoDBAInboundMailboxListener(String hmacSecret, AddedMessageReader messageReader, HttpClient httpClient, URI controller) {
        this.secret = hmacSecret;
        this.messageReader = messageReader;
        this.httpClient = httpClient;
        this.controller = controller;
    }

    @Override
    public Group getDefaultGroup() {
        return GROUP;
    }

    @Override
    public boolean isHandling(Event event) {
        return event instanceof MailboxEvents.Added
            && "incoming".equalsIgnoreCase(((MailboxEvents.Added) event).getMailboxPath().getName());
    }

    @Override
    public void event(Event event) throws Exception {
        if (isHandling(event)) {
            added(messageReader.read((MailboxEvents.Added) event));
        }
    }

    public void added(InboundMessage message) throws Exception {
        String incident = incidentFrom(message.references(), message.incidentHeader())
            .orElseThrow(() -> new IllegalArgumentException("missing Auto DBA incident identifier"));
        if (!MessageDigest.isEqual(hmac(incident).getBytes(StandardCharsets.UTF_8), message.token().getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("invalid Auto DBA HMAC token");
        }
        Command command = parse(message.body());
        String body = command.argument().isBlank() ? message.body() : command.argument() + "\n" + message.body();
        String json = "{\"command\":{\"incidentId\":\"" + escape(incident) + "\",\"verb\":\"" + command.verb()
            + "\",\"body\":\"" + escape(body) + "\"}}";
        HttpRequest request = HttpRequest.newBuilder(controller)
            .header("Content-Type", "application/json")
            .header("X-AutoDBA-Token", message.token())
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("controller rejected command: HTTP " + response.statusCode());
        }
    }

    static Optional<String> incidentFrom(String references, String fallback) {
        Matcher matcher = INCIDENT.matcher(references == null ? "" : references);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.ofNullable(fallback).filter(value -> !value.isBlank());
    }

    static Command parse(String body) {
        String trimmed = body == null ? "" : body.trim();
        String[] parts = trimmed.split("\\s+", 2);
        String verb = parts.length == 0 ? "COMMENT" : parts[0].toLowerCase(Locale.ROOT);
        String argument = parts.length == 2 ? parts[1].trim() : "";
        switch (verb) {
            case "approve": case "reject": case "pause": case "resume": case "escalate": case "comment":
                return new Command(verb.toUpperCase(Locale.ROOT), argument);
            case "runbook":
                if (argument.isBlank()) {
                    throw new IllegalArgumentException("runbook requires an id");
                }
                return new Command("RUNBOOK", argument);
            case "assign":
                if (argument.isBlank()) {
                    throw new IllegalArgumentException("assign requires a user");
                }
                return new Command("ASSIGN", argument);
            default:
                return new Command("COMMENT", trimmed);
        }
    }

    private String hmac(String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    public interface AddedMessageReader {
        InboundMessage read(MailboxEvents.Added event) throws Exception;
    }

    public record InboundMessage(String references, String incidentHeader, String token, String body) {
    }

    public record Command(String verb, String argument) {
    }

    public static class AutoDBAInboundMailboxListenerGroup extends Group {
    }
}
