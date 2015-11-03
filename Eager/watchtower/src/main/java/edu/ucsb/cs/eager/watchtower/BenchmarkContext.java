/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package edu.ucsb.cs.eager.watchtower;

import com.google.appengine.api.datastore.*;

public class BenchmarkContext {

    private boolean initialized = false;
    private boolean firstRecord = true;
    private boolean collectionEnabled = false;

    public BenchmarkContext(){
        DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();
        Query q = new Query(Constants.BM_CONTEXT_KIND, Constants.BM_CONTEXT_PARENT);
        Transaction txn = datastore.beginTransaction();
        try {
            PreparedQuery pq = datastore.prepare(txn, q);
            Entity entity = pq.asSingleEntity();
            if (entity != null) {
                firstRecord = (Boolean) entity.getProperty(
                        Constants.BM_CONTEXT_FIRST_RECORD);
                collectionEnabled = (Boolean) entity.getProperty(
                        Constants.BM_CONTEXT_COLLECTION_ENABLED);
                initialized = (Boolean) entity.getProperty(
                        Constants.BM_CONTEXT_INITIALIZED);
            }
            txn.commit();
        } finally {
            if (txn.isActive()) {
                txn.rollback();
            }
        }
    }

    public boolean isFirstRecord() {
        return firstRecord;
    }

    public void setFirstRecord(boolean firstRecord) {
        this.firstRecord = firstRecord;
    }

    public boolean isCollectionEnabled() {
        return collectionEnabled;
    }

    public void setCollectionEnabled(boolean collectionEnabled) {
        this.collectionEnabled = collectionEnabled;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    public boolean save() {
        DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();
        Transaction txn = datastore.beginTransaction();
        boolean result = true;
        try {
            Entity entity = new Entity(Constants.BM_CONTEXT_KIND,
                    Constants.BM_CONTEXT_KEY, Constants.BM_CONTEXT_PARENT);
            entity.setProperty(Constants.BM_CONTEXT_FIRST_RECORD, firstRecord);
            entity.setProperty(Constants.BM_CONTEXT_COLLECTION_ENABLED, collectionEnabled);
            entity.setProperty(Constants.BM_CONTEXT_INITIALIZED, initialized);
            datastore.put(txn, entity);
            txn.commit();
        } finally {
            if (txn.isActive()) {
                txn.rollback();
                result = false;
            }
        }
        return result;
    }

}
