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

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.backends.postgres.PostgresDataDefinition;
import org.apache.james.server.core.configuration.ConfigurationProvider;
import org.apache.james.user.api.UsersRepository;
import org.apache.james.user.lib.UsersDAO;
import org.apache.james.user.postgres.PostgresUserDataDefinition;
import org.apache.james.user.postgres.PostgresUsersDAO;
import org.apache.james.user.postgres.PostgresUsersRepository;
import org.apache.james.user.postgres.PostgresUsersRepositoryConfiguration;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.ProvidesIntoSet;

public class PostgresUsersRepositoryModule extends AbstractModule {

    public static AbstractModule USER_CONFIGURATION_MODULE = new AbstractModule() {
        @Provides
        @Singleton
        public PostgresUsersRepositoryConfiguration provideConfiguration(ConfigurationProvider configurationProvider) throws ConfigurationException {
            return PostgresUsersRepositoryConfiguration.from(
                configurationProvider.getConfiguration("usersrepository"));
        }
    };

    @Override
    public void configure() {
        bind(PostgresUsersRepository.class).in(Scopes.SINGLETON);
        bind(UsersRepository.class).to(PostgresUsersRepository.class);

        bind(PostgresUsersDAO.class).in(Scopes.SINGLETON);
        bind(UsersDAO.class).to(PostgresUsersDAO.class);

        Multibinder<PostgresDataDefinition> postgresDataDefinitions = Multibinder.newSetBinder(binder(), PostgresDataDefinition.class);
        postgresDataDefinitions.addBinding().toInstance(PostgresUserDataDefinition.MODULE);
    }

    @ProvidesIntoSet
    InitializationOperation configureInitialization(ConfigurationProvider configurationProvider, PostgresUsersRepository usersRepository) {
        return InitilizationOperationBuilder
            .forClass(PostgresUsersRepository.class)
            .init(() -> usersRepository.configure(configurationProvider.getConfiguration("usersrepository")));
    }

}
