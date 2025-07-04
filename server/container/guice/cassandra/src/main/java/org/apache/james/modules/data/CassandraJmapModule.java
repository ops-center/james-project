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

package org.apache.james.modules.data;

import java.io.FileNotFoundException;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.backends.cassandra.components.CassandraDataDefinition;
import org.apache.james.core.healthcheck.HealthCheck;
import org.apache.james.eventsourcing.Event;
import org.apache.james.eventsourcing.eventstore.EventStore;
import org.apache.james.eventsourcing.eventstore.dto.EventDTO;
import org.apache.james.eventsourcing.eventstore.dto.EventDTOModule;
import org.apache.james.jmap.api.filtering.FilteringManagement;
import org.apache.james.jmap.api.filtering.FilteringRuleSetDefineDTOModules;
import org.apache.james.jmap.api.filtering.FiltersDeleteUserDataTaskStep;
import org.apache.james.jmap.api.filtering.impl.EventSourcingFilteringManagement;
import org.apache.james.jmap.api.filtering.impl.FilterUsernameChangeTaskStep;
import org.apache.james.jmap.api.identity.CustomIdentityDAO;
import org.apache.james.jmap.api.identity.IdentityUserDeletionTaskStep;
import org.apache.james.jmap.api.projections.DefaultEmailQueryViewManager;
import org.apache.james.jmap.api.projections.EmailQueryView;
import org.apache.james.jmap.api.projections.EmailQueryViewManager;
import org.apache.james.jmap.api.projections.MessageFastViewProjection;
import org.apache.james.jmap.api.projections.MessageFastViewProjectionHealthCheck;
import org.apache.james.jmap.api.pushsubscription.PushDeleteUserDataTaskStep;
import org.apache.james.jmap.api.pushsubscription.PushSubscriptionRepository;
import org.apache.james.jmap.api.upload.UploadRepository;
import org.apache.james.jmap.api.upload.UploadUsageRepository;
import org.apache.james.jmap.cassandra.change.CassandraEmailChangeDataDefinition;
import org.apache.james.jmap.cassandra.change.CassandraMailboxChangeDataDefinition;
import org.apache.james.jmap.cassandra.filtering.CassandraFilteringProjection;
import org.apache.james.jmap.cassandra.filtering.CassandraFilteringProjectionDataDefinition;
import org.apache.james.jmap.cassandra.identity.CassandraCustomIdentityDAO;
import org.apache.james.jmap.cassandra.identity.CassandraCustomIdentityDataDefinition;
import org.apache.james.jmap.cassandra.projections.CassandraEmailQueryView;
import org.apache.james.jmap.cassandra.projections.CassandraEmailQueryViewDataDefinition;
import org.apache.james.jmap.cassandra.projections.CassandraMessageFastViewProjection;
import org.apache.james.jmap.cassandra.projections.CassandraMessageFastViewProjectionDataDefinition;
import org.apache.james.jmap.cassandra.projections.CassandraMessageFastViewProjectionDeletionCallback;
import org.apache.james.jmap.cassandra.pushsubscription.CassandraPushSubscriptionDataDefinition;
import org.apache.james.jmap.cassandra.pushsubscription.CassandraPushSubscriptionRepository;
import org.apache.james.jmap.cassandra.upload.CassandraUploadRepository;
import org.apache.james.jmap.cassandra.upload.CassandraUploadUsageRepository;
import org.apache.james.jmap.cassandra.upload.UploadDAO;
import org.apache.james.jmap.cassandra.upload.UploadDataDefinition;
import org.apache.james.mailbox.cassandra.DeleteMessageListener;
import org.apache.james.user.api.DeleteUserDataTaskStep;
import org.apache.james.user.api.UsernameChangeTaskStep;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.Multibinder;

public class CassandraJmapModule extends AbstractModule {
    @Override
    protected void configure() {
        bind(CassandraUploadRepository.class).in(Scopes.SINGLETON);
        bind(UploadDAO.class).in(Scopes.SINGLETON);
        bind(UploadRepository.class).to(CassandraUploadRepository.class);
        bind(UploadUsageRepository.class).to(CassandraUploadUsageRepository.class);

        bind(CassandraCustomIdentityDAO.class).in(Scopes.SINGLETON);
        bind(CustomIdentityDAO.class).to(CassandraCustomIdentityDAO.class);

        bind(CassandraFilteringProjection.class).in(Scopes.SINGLETON);
        bind(EventSourcingFilteringManagement.ReadProjection.class).to(CassandraFilteringProjection.class);

        bind(CassandraPushSubscriptionRepository.class).in(Scopes.SINGLETON);
        bind(PushSubscriptionRepository.class).to(CassandraPushSubscriptionRepository.class);

        bind(CassandraMessageFastViewProjection.class).in(Scopes.SINGLETON);
        bind(MessageFastViewProjection.class).to(CassandraMessageFastViewProjection.class);
        bind(MessageFastViewProjectionHealthCheck.class).in(Scopes.SINGLETON);
        Multibinder.newSetBinder(binder(), HealthCheck.class)
            .addBinding()
            .to(MessageFastViewProjectionHealthCheck.class);

        bind(CassandraEmailQueryView.class).in(Scopes.SINGLETON);https://github.com/apache/james-project/blob/master/server/container/guice/data-cassandra/src/main/java/org/apache/james/modules/data/CassandraUsersRepositoryModule.java
        bind(EmailQueryView.class).to(CassandraEmailQueryView.class);
        bind(DefaultEmailQueryViewManager.class).in(Scopes.SINGLETON);
        bind(EmailQueryViewManager.class).to(DefaultEmailQueryViewManager.class);

        Multibinder<CassandraDataDefinition> cassandraDataDefinitions = Multibinder.newSetBinder(binder(), CassandraDataDefinition.class);
        cassandraDataDefinitions.addBinding().toInstance(CassandraMessageFastViewProjectionDataDefinition.MODULE);
        cassandraDataDefinitions.addBinding().toInstance(CassandraEmailQueryViewDataDefinition.MODULE);
        cassandraDataDefinitions.addBinding().toInstance(CassandraMailboxChangeDataDefinition.MODULE);
        cassandraDataDefinitions.addBinding().toInstance(CassandraEmailChangeDataDefinition.MODULE);
        cassandraDataDefinitions.addBinding().toInstance(UploadDataDefinition.MODULE);
        cassandraDataDefinitions.addBinding().toInstance(CassandraPushSubscriptionDataDefinition.MODULE);
        cassandraDataDefinitions.addBinding().toInstance(CassandraFilteringProjectionDataDefinition.MODULE);
        cassandraDataDefinitions.addBinding().toInstance(CassandraCustomIdentityDataDefinition.MODULE());

        Multibinder<EventDTOModule<? extends Event, ? extends EventDTO>> eventDTOModuleBinder = Multibinder.newSetBinder(binder(), new TypeLiteral<>() {});
        eventDTOModuleBinder.addBinding().toInstance(FilteringRuleSetDefineDTOModules.FILTERING_RULE_SET_DEFINED);
        eventDTOModuleBinder.addBinding().toInstance(FilteringRuleSetDefineDTOModules.FILTERING_INCREMENT);

        Multibinder.newSetBinder(binder(), DeleteMessageListener.DeletionCallback.class)
            .addBinding()
            .to(CassandraMessageFastViewProjectionDeletionCallback.class);

        Multibinder.newSetBinder(binder(), UsernameChangeTaskStep.class)
            .addBinding()
            .to(FilterUsernameChangeTaskStep.class);

        Multibinder<DeleteUserDataTaskStep> deleteUserDataTaskSteps = Multibinder.newSetBinder(binder(), DeleteUserDataTaskStep.class);
        deleteUserDataTaskSteps.addBinding().to(FiltersDeleteUserDataTaskStep.class);
        deleteUserDataTaskSteps.addBinding().to(IdentityUserDeletionTaskStep.class);
        deleteUserDataTaskSteps.addBinding().to(PushDeleteUserDataTaskStep.class);
        bind(FilteringManagement.class).to(EventSourcingFilteringManagement.class).asEagerSingleton();
    }

    @Singleton
    @Provides
    EventSourcingFilteringManagement provideFilteringManagement(EventStore eventStore, CassandraFilteringProjection cassandraFilteringProjection,
                                                                PropertiesProvider propertiesProvider) throws ConfigurationException {
        if (cassandraFilterProjectionActivated(propertiesProvider)) {
            return new EventSourcingFilteringManagement(eventStore, cassandraFilteringProjection);
        } else {
            return new EventSourcingFilteringManagement(eventStore, new EventSourcingFilteringManagement.NoReadProjection(eventStore));
        }
    }

    private boolean cassandraFilterProjectionActivated(PropertiesProvider propertiesProvider) throws ConfigurationException {
        try {
            return propertiesProvider.getConfiguration("jmap")
                .getBoolean("cassandra.filter.projection.activated", false);
        } catch (FileNotFoundException e) {
            return false;
        }
    }
}
