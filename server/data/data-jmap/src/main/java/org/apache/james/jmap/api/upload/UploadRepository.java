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

package org.apache.james.jmap.api.upload;

import java.io.InputStream;
import java.time.Duration;

import org.apache.james.core.Username;
import org.apache.james.jmap.api.model.Upload;
import org.apache.james.jmap.api.model.UploadId;
import org.apache.james.jmap.api.model.UploadMetaData;
import org.apache.james.mailbox.model.ContentType;
import org.reactivestreams.Publisher;

public interface UploadRepository {
    Publisher<UploadMetaData> upload(InputStream data, ContentType contentType, Username user);

    Publisher<Upload> retrieve(UploadId id, Username user);

    Publisher<Boolean> delete(UploadId id, Username user);

    Publisher<UploadMetaData> listUploads(Username user);

    Publisher<Void> deleteByUploadDateBefore(Duration expireDuration);
}

