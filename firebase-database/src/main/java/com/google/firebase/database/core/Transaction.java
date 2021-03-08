// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.database.core;

import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabaseException;
import com.google.firebase.database.InternalHelpers;
import com.google.firebase.database.Query;
import com.google.firebase.database.core.utilities.Pair;
import com.google.firebase.database.snapshot.IndexedNode;
import com.google.firebase.database.snapshot.Node;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * An internal transaction object responsible for tracking data operations performed in a user
 * transaction callback. All data operations are executed on the transaction thread-pool.
 */
public class Transaction {

  private Repo repo;

  private List<Pair<Query, String>> queries;

  private List<Pair<DatabaseReference, Object>> writes;

  private long transactionStartWriteId;

  private static final Executor defaultExecutor = createDefaultExecutor();

  public Transaction(Repo repo) {
    this.repo = repo;
    this.transactionStartWriteId = this.repo.getNextWriteId();
  }

  private static Executor createDefaultExecutor() {
    int corePoolSize = 5;
    int maxPoolSize = corePoolSize;
    int keepAliveSeconds = 1;
    LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            corePoolSize, maxPoolSize, keepAliveSeconds, TimeUnit.SECONDS, queue);
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  public static Executor getDefaultExecutor() {
    return defaultExecutor;
  }

  public Task<DataSnapshot> get(@NonNull Query query) {
    if (writes.size() != 0) {
      return Tasks.forException(
          new FirebaseDatabaseException(
              "Firebase Database transactions require all reads to be executed before all writes",
              FirebaseDatabaseException.Code.InvalidArgument));
    }
    // Note that query.get runs on the repo's runloop, but we record the results
    // here, in the transaction executor.
    Executor executor = Runnable::run;
    return repo.getNode(query)
        .continueWith(
            executor,
            (Task<Node> nodeTask) -> {
              Node node = nodeTask.getResult();
              recordQueryResult(query, node);
              return InternalHelpers.createDataSnapshot(query.getRef(), IndexedNode.from(node));
            });
  }

  public void set(@NonNull DatabaseReference ref, @NonNull Object data) {
    writes.add(new Pair(ref, data));
  }

  public void recordQueryResult(Query query, Node node) {
    queries.add(new Pair(query, node.getHash()));
  }

  public Task<Void> commit() {
    // TODO(wyszynski): validate read set hasn't been modified, do the multipath update.
    return null;
  }
}