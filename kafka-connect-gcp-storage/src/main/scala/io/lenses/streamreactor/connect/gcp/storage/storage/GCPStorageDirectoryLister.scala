/*
 * Copyright 2017-2024 Lenses.io Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lenses.streamreactor.connect.gcp.storage.storage

import cats.effect.IO
import com.google.cloud.storage.Storage
import com.google.cloud.storage.Storage.BlobListOption
import com.typesafe.scalalogging.LazyLogging
import io.lenses.streamreactor.connect.cloud.common.config.ConnectorTaskId
import io.lenses.streamreactor.connect.cloud.common.model.location.CloudLocation
import io.lenses.streamreactor.connect.cloud.common.storage.DirectoryLister

import scala.jdk.CollectionConverters.IterableHasAsScala

class GCPStorageDirectoryLister(connectorTaskId: ConnectorTaskId, storage: Storage)
    extends LazyLogging
    with DirectoryLister {

  /**
    * @param wildcardExcludes allows ignoring paths containing certain strings.  Mainly it is used to prevent us from reading anything inside the .indexes key prefix, as these should be ignored by the source.
    */
  override def findDirectories(
    bucketAndPrefix:  CloudLocation,
    filesLimit:       Int,
    recurseLevels:    Int,
    exclude:          Set[String],
    wildcardExcludes: Set[String],
  ): IO[Set[String]] = {

    def listSubdirs(prefix: String, recurseLevels: Int): Iterable[String] = {

      val blobListOptions = BlobListOption.dedupe(
        BlobListOption.delimiter("/"),
        BlobListOption.pageSize(filesLimit.toLong),
        BlobListOption.prefix(prefix),
        BlobListOption.currentDirectory(),
      )

      val foundResults = storage
        .get(bucketAndPrefix.bucket)
        .list(blobListOptions: _*)
        .iterateAll()
        .asScala
        .filter(_.isDirectory)
        .map(_.getName)
        .toList
        .filter { prefix =>
          connectorTaskId.ownsDir(prefix) && !exclude.contains(prefix) && !wildcardExcludes.exists(we =>
            prefix.contains(we),
          )
        }

      foundResults.flatMap {
        case d: String if recurseLevels > 1 =>
          listSubdirs(d, recurseLevels - 1)
        case _ =>
          foundResults
      }

    }

    for {
      iterator <- IO(listSubdirs(bucketAndPrefix.prefix.getOrElse(""), recurseLevels))
    } yield iterator.toSet ++ bucketAndPrefix.prefix

  }

}
