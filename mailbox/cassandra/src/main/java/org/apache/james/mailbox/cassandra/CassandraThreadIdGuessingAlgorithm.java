/******************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one     *
 * or more contributor license agreements.  See the NOTICE file   *
 * distributed with this work for additional information          *
 * regarding copyright ownership.  The ASF licenses this file     *
 * to you under the Apache License, Version 2.0 (the              *
 * "License"); you may not use this file except in compliance     *
 * with the License.  You may obtain a copy of the License at     *
 *                                                                *
 * http://www.apache.org/licenses/LICENSE-2.0                     *
 *                                                                *
 * Unless required by applicable law or agreed to in writing,     *
 * software distributed under the License is distributed on an    *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY         *
 * KIND, either express or implied.  See the License for the      *
 * specific language governing permissions and limitations        *
 * under the License.                                             *
 ******************************************************************/

package org.apache.james.mailbox.cassandra;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageIdManager;
import org.apache.james.mailbox.cassandra.mail.CassandraThreadDAO;
import org.apache.james.mailbox.cassandra.mail.CassandraThreadLookupDAO;
import org.apache.james.mailbox.exception.ThreadNotFoundException;
import org.apache.james.mailbox.model.FetchGroup;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MessageResult;
import org.apache.james.mailbox.model.ThreadId;
import org.apache.james.mailbox.store.mail.ThreadIdGuessingAlgorithm;
import org.apache.james.mailbox.store.mail.model.MimeMessageId;
import org.apache.james.mailbox.store.mail.model.Subject;
import org.apache.james.mailbox.store.search.SearchUtil;

import com.google.common.hash.Hashing;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

public class CassandraThreadIdGuessingAlgorithm implements ThreadIdGuessingAlgorithm {
    private static final boolean DISABLE_THREADS = Boolean.valueOf(System.getProperty("james.mailbox.threads.disable", "false"));

    private final MessageIdManager messageIdManager;
    private final CassandraThreadDAO threadDAO;
    private final CassandraThreadLookupDAO threadLookupDAO;

    @Inject
    public CassandraThreadIdGuessingAlgorithm(MessageIdManager messageIdManager, CassandraThreadDAO threadDAO, CassandraThreadLookupDAO threadLookupDAO) {
        this.messageIdManager = messageIdManager;
        this.threadDAO = threadDAO;
        this.threadLookupDAO = threadLookupDAO;
    }

    @Override
    public Mono<ThreadId> guessThreadIdReactive(MessageId messageId, Optional<MimeMessageId> mimeMessageId, Optional<MimeMessageId> inReplyTo, Optional<List<MimeMessageId>> references, Optional<Subject> subject, MailboxSession session) {
        Set<Integer> hashMimeMessageIds = buildMimeMessageIdSet(mimeMessageId, inReplyTo, references)
            .stream()
            .map(mimeMessageId1 -> Hashing.murmur3_32_fixed().hashBytes(mimeMessageId1.getValue().getBytes()).asInt())
            .collect(Collectors.toSet());

        Optional<Integer> hashBaseSubject = subject.map(value -> new Subject(SearchUtil.getBaseSubject(value.getValue())))
            .map(subject1 -> Hashing.murmur3_32_fixed().hashBytes(subject1.getValue().getBytes()).asInt());

        return Flux.from(threadDAO.selectSome(session.getUser(), hashMimeMessageIds))
            .filter(pair -> pair.getLeft().equals(hashBaseSubject))
            .next()
            .map(Pair::getRight)
            .switchIfEmpty(Mono.just(ThreadId.fromBaseMessageId(messageId)))
            .flatMap(threadId -> threadDAO
                .insertSome(session.getUser(), hashMimeMessageIds, messageId, threadId, hashBaseSubject)
                .then(threadLookupDAO.insert(messageId, threadId, session.getUser(), hashMimeMessageIds))
                .then(Mono.just(threadId)));
    }

    @Override
    public Flux<MessageId> getMessageIdsInThread(ThreadId threadId, MailboxSession session) {
        if (DISABLE_THREADS) {
            return Flux.just(threadId.getBaseMessageId());
        }

        BiConsumer<Collection<MessageId>, SynchronousSink<Collection<MessageId>>> throwIfEmpty = (ids, sink) -> {
            // Handle umpopulated lookup dao
            if (ids.isEmpty()) {
                sink.error(new ThreadNotFoundException(threadId));
            } else {
                sink.next(ids);
            }
        };
        return threadLookupDAO.selectAll(threadId)
            .collectList()
            .handle(throwIfEmpty)
            .flatMapMany(messageIds -> Flux.from(messageIdManager.getMessagesReactive(messageIds, FetchGroup.MINIMAL, session)))
            .sort(Comparator.comparing(MessageResult::getInternalDate).thenComparing(m -> m.getMessageId().serialize()))
            .map(MessageResult::getMessageId)
            .distinct()
            .switchIfEmpty(Mono.error(() -> new ThreadNotFoundException(threadId)));
    }

    @Override
    public Flux<MessageId> getLatestMessageIdsInThread(ThreadId threadId, MailboxSession session, int limit) {
        return null;
    }


    private Set<MimeMessageId> buildMimeMessageIdSet(Optional<MimeMessageId> mimeMessageId, Optional<MimeMessageId> inReplyTo, Optional<List<MimeMessageId>> references) {
        Set<MimeMessageId> mimeMessageIds = new HashSet<>();
        mimeMessageId.ifPresent(mimeMessageIds::add);
        inReplyTo.ifPresent(mimeMessageIds::add);
        references.ifPresent(mimeMessageIds::addAll);
        return mimeMessageIds;
    }
}