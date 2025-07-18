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

package org.apache.james.mailbox.cassandra;

import static org.apache.james.backends.cassandra.init.configuration.JamesExecutionProfiles.ConsistencyChoice.STRONG;
import static org.apache.james.backends.cassandra.init.configuration.JamesExecutionProfiles.ConsistencyChoice.WEAK;
import static org.apache.james.util.FunctionalUtils.negate;

import java.util.Date;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.james.backends.cassandra.init.configuration.CassandraConfiguration;
import org.apache.james.backends.cassandra.init.configuration.JamesExecutionProfiles;
import org.apache.james.blob.api.BlobId;
import org.apache.james.blob.api.BlobStore;
import org.apache.james.core.Username;
import org.apache.james.events.Event;
import org.apache.james.events.EventListener;
import org.apache.james.events.Group;
import org.apache.james.mailbox.acl.ACLDiff;
import org.apache.james.mailbox.cassandra.ids.CassandraId;
import org.apache.james.mailbox.cassandra.ids.CassandraMessageId;
import org.apache.james.mailbox.cassandra.mail.ACLMapper;
import org.apache.james.mailbox.cassandra.mail.CassandraApplicableFlagDAO;
import org.apache.james.mailbox.cassandra.mail.CassandraAttachmentDAOV2;
import org.apache.james.mailbox.cassandra.mail.CassandraDeletedMessageDAO;
import org.apache.james.mailbox.cassandra.mail.CassandraFirstUnseenDAO;
import org.apache.james.mailbox.cassandra.mail.CassandraMailboxCounterDAO;
import org.apache.james.mailbox.cassandra.mail.CassandraMailboxRecentsDAO;
import org.apache.james.mailbox.cassandra.mail.CassandraMessageDAOV3;
import org.apache.james.mailbox.cassandra.mail.CassandraMessageIdDAO;
import org.apache.james.mailbox.cassandra.mail.CassandraMessageIdToImapUidDAO;
import org.apache.james.mailbox.cassandra.mail.CassandraThreadDAO;
import org.apache.james.mailbox.cassandra.mail.CassandraThreadLookupDAO;
import org.apache.james.mailbox.cassandra.mail.CassandraUserMailboxRightsDAO;
import org.apache.james.mailbox.cassandra.mail.MessageRepresentation;
import org.apache.james.mailbox.events.MailboxEvents.Expunged;
import org.apache.james.mailbox.events.MailboxEvents.MailboxDeletion;
import org.apache.james.mailbox.model.MailboxACL;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.ThreadId;
import org.apache.james.mailbox.store.mail.MessageMapper;
import org.apache.james.util.streams.Limit;
import org.reactivestreams.Publisher;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * This listener cleans Cassandra metadata up. It retrieves dandling unreferenced metadata after the delete operation
 * had been conducted out. Then it deletes the lower levels first so that upon failures undeleted metadata can still be
 * reached.
 *
 * This cleanup is not needed for strict correctness from a MailboxManager point of view thus it could be carried out
 * asynchronously, via mailbox listeners so that it can be retried.
 *
 * Mailbox listener failures lead to eventBus retrying their execution, it ensures the result of the deletion to be
 * idempotent.
 */
public class DeleteMessageListener implements EventListener.ReactiveGroupEventListener {
    private static final Optional<CassandraId> ALL_MAILBOXES = Optional.empty();

    public static class DeleteMessageListenerGroup extends Group {

    }

    public static class DeletedMessageCopyCommand {
        public static DeletedMessageCopyCommand of(MessageRepresentation message, MailboxId mailboxId, Username owner) {
            return new DeletedMessageCopyCommand(message.getMessageId(), mailboxId, owner, message.getInternalDate(),
                message.getSize(), !message.getAttachments().isEmpty(), message.getHeaderId(), message.getBodyId());
        }

        private final MessageId messageId;
        private final MailboxId mailboxId;
        private final Username owner;
        private final Date internalDate;
        private final long size;
        private final boolean hasAttachments;
        private final BlobId headerId;
        private final BlobId bodyId;

        public DeletedMessageCopyCommand(MessageId messageId, MailboxId mailboxId, Username owner, Date internalDate, long size, boolean hasAttachments, BlobId headerId, BlobId bodyId) {
            this.messageId = messageId;
            this.mailboxId = mailboxId;
            this.owner = owner;
            this.internalDate = internalDate;
            this.size = size;
            this.hasAttachments = hasAttachments;
            this.headerId = headerId;
            this.bodyId = bodyId;
        }

        public Username getOwner() {
            return owner;
        }

        public MessageId getMessageId() {
            return messageId;
        }

        public MailboxId getMailboxId() {
            return mailboxId;
        }

        public Date getInternalDate() {
            return internalDate;
        }

        public long getSize() {
            return size;
        }

        public boolean hasAttachments() {
            return hasAttachments;
        }

        public BlobId getHeaderId() {
            return headerId;
        }

        public BlobId getBodyId() {
            return bodyId;
        }
    }

    @FunctionalInterface
    public interface DeletionCallback {
        default Mono<Void> forMessage(MessageRepresentation message, MailboxId mailboxId, Username owner) {
            return forMessage(DeletedMessageCopyCommand.of(message, mailboxId, owner));
        }
        
        Mono<Void> forMessage(DeletedMessageCopyCommand copyCommand);
    }

    private final CassandraThreadDAO threadDAO;
    private final CassandraThreadLookupDAO threadLookupDAO;
    private final CassandraMessageIdToImapUidDAO imapUidDAO;
    private final CassandraMessageIdDAO messageIdDAO;
    private final CassandraMessageDAOV3 messageDAOV3;
    private final CassandraAttachmentDAOV2 attachmentDAO;
    private final ACLMapper aclMapper;
    private final CassandraUserMailboxRightsDAO rightsDAO;
    private final CassandraApplicableFlagDAO applicableFlagDAO;
    private final CassandraFirstUnseenDAO firstUnseenDAO;
    private final CassandraDeletedMessageDAO deletedMessageDAO;
    private final CassandraMailboxCounterDAO counterDAO;
    private final CassandraMailboxRecentsDAO recentsDAO;
    private final BlobStore blobStore;
    private final CassandraConfiguration cassandraConfiguration;
    private final Set<DeletionCallback> deletionCallbackList;

    @Inject
    public DeleteMessageListener(CassandraThreadDAO threadDAO, CassandraThreadLookupDAO threadLookupDAO,
                                 CassandraMessageIdToImapUidDAO imapUidDAO, CassandraMessageIdDAO messageIdDAO,
                                 CassandraMessageDAOV3 messageDAOV3, CassandraAttachmentDAOV2 attachmentDAO,
                                 ACLMapper aclMapper,
                                 CassandraUserMailboxRightsDAO rightsDAO, CassandraApplicableFlagDAO applicableFlagDAO,
                                 CassandraFirstUnseenDAO firstUnseenDAO, CassandraDeletedMessageDAO deletedMessageDAO,
                                 CassandraMailboxCounterDAO counterDAO, CassandraMailboxRecentsDAO recentsDAO, BlobStore blobStore,
                                 CassandraConfiguration cassandraConfiguration, Set<DeletionCallback> deletionCallbackList) {
        this.threadDAO = threadDAO;
        this.threadLookupDAO = threadLookupDAO;
        this.imapUidDAO = imapUidDAO;
        this.messageIdDAO = messageIdDAO;
        this.messageDAOV3 = messageDAOV3;
        this.attachmentDAO = attachmentDAO;
        this.aclMapper = aclMapper;
        this.rightsDAO = rightsDAO;
        this.applicableFlagDAO = applicableFlagDAO;
        this.firstUnseenDAO = firstUnseenDAO;
        this.deletedMessageDAO = deletedMessageDAO;
        this.counterDAO = counterDAO;
        this.recentsDAO = recentsDAO;
        this.blobStore = blobStore;
        this.cassandraConfiguration = cassandraConfiguration;
        this.deletionCallbackList = deletionCallbackList;
    }

    @Override
    public Group getDefaultGroup() {
        return new DeleteMessageListenerGroup();
    }

    @Override
    public boolean isHandling(Event event) {
        return event instanceof Expunged || event instanceof MailboxDeletion;
    }

    @Override
    public Publisher<Void> reactiveEvent(Event event) {
        if (event instanceof Expunged) {
            Expunged expunged = (Expunged) event;

            return handleMessageDeletion(expunged);
        }
        if (event instanceof MailboxDeletion) {
            MailboxDeletion mailboxDeletion = (MailboxDeletion) event;

            CassandraId mailboxId = (CassandraId) mailboxDeletion.getMailboxId();

            return handleMailboxDeletion(mailboxId, mailboxDeletion.getMailboxPath());
        }
        return Mono.empty();
    }

    private Mono<Void> handleMailboxDeletion(CassandraId mailboxId, MailboxPath path) {
        int prefetch = 1;
        return Flux.mergeDelayError(prefetch,
                messageIdDAO.retrieveMessages(mailboxId, MessageRange.all(), Limit.unlimited())
                    .concatMap(metadata -> handleMessageDeletionAsPartOfMailboxDeletion((CassandraMessageId) metadata.getComposedMessageId().getComposedMessageId().getMessageId(),
                            metadata.getComposedMessageId().getThreadId(), mailboxId, path.getUser())
                        .then(imapUidDAO.delete((CassandraMessageId) metadata.getComposedMessageId().getComposedMessageId().getMessageId(), mailboxId))
                        .then(messageIdDAO.delete(mailboxId, metadata.getComposedMessageId().getComposedMessageId().getUid()))),
                deleteAcl(mailboxId),
                applicableFlagDAO.delete(mailboxId),
                firstUnseenDAO.removeAll(mailboxId),
                deletedMessageDAO.removeAll(mailboxId),
                counterDAO.delete(mailboxId),
                recentsDAO.delete(mailboxId))
            .then();
    }

    private Mono<Void> handleMessageDeletion(Expunged expunged) {
        return Flux.fromIterable(expunged.getExpunged().values())
            .concatMap(metaData -> handleMessageDeletion((CassandraMessageId) metaData.getMessageId(),
                expunged.getMailboxId(), metaData.getThreadId(), expunged.getMailboxPath().getUser()))
            .then();
    }

    private Mono<Void> deleteAcl(CassandraId mailboxId) {
        return aclMapper.getACL(mailboxId)
            .flatMap(acl -> rightsDAO.update(mailboxId, ACLDiff.computeDiff(acl, MailboxACL.EMPTY))
                .then(aclMapper.delete(mailboxId)));
    }

    private Mono<Void> handleMessageDeletion(CassandraMessageId messageId, MailboxId mailboxId, ThreadId threadId, Username owner) {
        return Mono.just(messageId)
            .filterWhen(this::isReferenced)
            .flatMap(id -> readMessage(id)
                .flatMap(message -> Flux.fromIterable(deletionCallbackList).concatMap(callback -> callback.forMessage(message, mailboxId, owner)).then().thenReturn(message))
                .flatMap(message -> deleteUnreferencedAttachments(message).thenReturn(message))
                .flatMap(this::deleteMessageBlobs)
                .then(messageDAOV3.delete(messageId))
                .then(threadLookupDAO.selectOneRow(threadId, messageId)
                    .flatMap(key -> threadDAO.deleteSome(key.getUsername(), key.getMimeMessageIds())
                        .collectList()))
                .then(threadLookupDAO.deleteOneRow(threadId, messageId)));
    }

    private Mono<Void> handleMessageDeletionAsPartOfMailboxDeletion(CassandraMessageId messageId, ThreadId threadId, CassandraId excludedId, Username owner) {
        return Mono.just(messageId)
            .filterWhen(id -> isReferenced(id, excludedId))
            .flatMap(id -> readMessage(id)
                .flatMap(message -> Flux.fromIterable(deletionCallbackList).concatMap(callback -> callback.forMessage(message, excludedId, owner)).then().thenReturn(message))
                .flatMap(message -> deleteUnreferencedAttachments(message).thenReturn(message))
                .flatMap(this::deleteMessageBlobs)
                .then(messageDAOV3.delete(messageId))
                .then(threadLookupDAO.selectOneRow(threadId, messageId)
                    .flatMap(key -> threadDAO.deleteSome(key.getUsername(), key.getMimeMessageIds())
                        .collectList()))
                .then(threadLookupDAO.deleteOneRow(threadId, messageId)));
    }

    private Mono<MessageRepresentation> deleteMessageBlobs(MessageRepresentation message) {
        return Flux.merge(
                blobStore.delete(blobStore.getDefaultBucketName(), message.getHeaderId()),
                blobStore.delete(blobStore.getDefaultBucketName(), message.getBodyId()))
            .then()
            .thenReturn(message);
    }

    private Mono<MessageRepresentation> readMessage(CassandraMessageId id) {
        return messageDAOV3.retrieveMessage(id, MessageMapper.FetchType.METADATA);
    }

    private Mono<Void> deleteUnreferencedAttachments(MessageRepresentation message) {
        return Flux.fromIterable(message.getAttachments())
            .concatMap(attachment -> attachmentDAO.getAttachment(attachment.getAttachmentId())
                .map(CassandraAttachmentDAOV2.DAOAttachment::getBlobId)
                .flatMap(blobId -> Mono.from(blobStore.delete(blobStore.getDefaultBucketName(), blobId)))
                .then(attachmentDAO.delete(attachment.getAttachmentId())))
            .then();
    }

    private Mono<Boolean> isReferenced(CassandraMessageId id) {
        return imapUidDAO.retrieve(id, ALL_MAILBOXES, chooseReadConsistencyUponWrites())
            .hasElements()
            .map(negate());
    }

    private Mono<Boolean> isReferenced(CassandraMessageId id, CassandraId excludedId) {
        return imapUidDAO.retrieve(id, ALL_MAILBOXES, chooseReadConsistencyUponWrites())
            .filter(metadata -> !metadata.getComposedMessageId().getComposedMessageId().getMailboxId().equals(excludedId))
            .hasElements()
            .map(negate());
    }

    private JamesExecutionProfiles.ConsistencyChoice chooseReadConsistencyUponWrites() {
        if (cassandraConfiguration.isMessageWriteStrongConsistency()) {
            return STRONG;
        }
        return WEAK;
    }
}
