// Copyright (c) 2017, Xiaomi, Inc.  All rights reserved.
// This source code is licensed under the Apache License Version 2.0, which
// can be found in the LICENSE file in the root directory of this source tree.
package com.xiaomi.infra.pegasus.client;

import com.xiaomi.infra.pegasus.rpc.ReplicationException;
import com.xiaomi.infra.pegasus.rpc.Table;
import com.xiaomi.infra.pegasus.apps.*;

import com.xiaomi.infra.pegasus.base.blob;
import com.xiaomi.infra.pegasus.base.error_code;
import com.xiaomi.infra.pegasus.base.gpid;

import com.xiaomi.infra.pegasus.operator.*;
import com.xiaomi.infra.pegasus.tools.tools;

import io.netty.util.concurrent.*;
import org.apache.commons.lang3.Validate;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author sunweijie
 *
 * Implementation of {@link PegasusTableInterface}.
 */
public class PegasusTable implements PegasusTableInterface {
    private PegasusClient client;
    private Table table;
    private int defaultTimeout;

    public PegasusTable(PegasusClient client, Table table) {
        this.client = client;
        this.table = table;
        this.defaultTimeout = table.getDefaultTimeout();
    }

    @Override
    public Future<Boolean> asyncExist(byte[] hashKey, byte[] sortKey, int timeout) {
        final DefaultPromise<Boolean> promise = table.newPromise();
        asyncTTL(hashKey, sortKey, timeout).addListener(
                new TTLListener() {
                    @Override
                    public void operationComplete(Future<Integer> future) throws Exception {
                        if (future.isSuccess()) {
                            promise.setSuccess(future.get()!=-2);
                        }
                        else {
                            promise.setFailure(future.cause());
                        }
                    }
                }
        );
        return promise;
    }

    @Override
    public Future<Long> asyncSortKeyCount(byte[] hashKey, int timeout) {
        final DefaultPromise<Long> promise = table.newPromise();
        if (hashKey == null || hashKey.length == 0) {
            promise.setFailure(new PException("Invalid parameter: hashKey should not be null or empty"));
            return promise;
        }
        if (hashKey.length >= 0xFFFF) {
            promise.setFailure(new PException("Invalid parameter: hashKey length should be less than UINT16_MAX"));
            return promise;
        }

        blob hashKeyRequest = new blob(hashKey);
        gpid pid = table.getHashKeyGpid(hashKey);
        rrdb_sortkey_count_operator op = new rrdb_sortkey_count_operator(pid, table.getTableName(), hashKeyRequest);
        Table.ClientOPCallback callback = new Table.ClientOPCallback() {
            @Override
            public void onCompletion(client_operator clientOP) {
                rrdb_sortkey_count_operator op = (rrdb_sortkey_count_operator) clientOP;
                if (op.rpc_error.errno != error_code.error_types.ERR_OK) {
                    promise.setFailure(new PException(new ReplicationException(op.rpc_error.errno)));
                }
                else if (op.get_response().error != 0) {
                    promise.setFailure(new PException("rocksdb error: " + op.get_response().error));
                }
                else {
                    promise.setSuccess(op.get_response().count);
                }
            }
        };

        table.asyncOperate(op, callback, timeout);
        return promise;
    }

    @Override
    public Future<byte[]> asyncGet(byte[] hashKey, byte[] sortKey, int timeout/*ms*/) {
        final DefaultPromise<byte[]> promise = table.newPromise();
        blob request = new blob(PegasusClient.generateKey(hashKey, sortKey));
        gpid gpid = table.getGpid(request.data);
        rrdb_get_operator op = new rrdb_get_operator(gpid, table.getTableName(), request);
        Table.ClientOPCallback callback = new Table.ClientOPCallback() {
            @Override
            public void onCompletion(client_operator clientOP) {
                rrdb_get_operator gop = (rrdb_get_operator) clientOP;
                if (gop.rpc_error.errno != error_code.error_types.ERR_OK) {
                    promise.setFailure(new PException(new ReplicationException(gop.rpc_error.errno)));
                }
                else if (gop.get_response().error == 1) {//rocksdb::kNotFound
                    promise.setSuccess(null);
                }
                else if (gop.get_response().error != 0) {
                    promise.setFailure(new PException("rocksdb error: " + gop.get_response().error));
                }
                else {
                    promise.setSuccess(gop.get_response().value.data);
                }
            }
        };

        table.asyncOperate(op, callback, timeout);
        return promise;
    }

    @Override
    public Future<Void> asyncSet(byte[] hashKey, byte[] sortKey, byte[] value, int ttlSeconds, int timeout/*ms*/) {
        final DefaultPromise<Void> promise = table.newPromise();
        if (value == null) {
            promise.setFailure(new PException("Invalid parameter: value should not be null"));
            return promise;
        }

        blob k = new blob(PegasusClient.generateKey(hashKey, sortKey));
        blob v = new blob(value);
        int expireSeconds = (ttlSeconds == 0 ? 0 : ttlSeconds + (int) tools.epoch_now());
        update_request req = new update_request(k, v, expireSeconds);

        gpid gpid = table.getGpid(k.data);
        rrdb_put_operator op = new rrdb_put_operator(gpid, table.getTableName(), req);
        table.asyncOperate(
                op,
                new Table.ClientOPCallback() {
                    @Override
                    public void onCompletion(client_operator clientOP) {
                        rrdb_put_operator gop = (rrdb_put_operator) clientOP;
                        if (gop.rpc_error.errno != error_code.error_types.ERR_OK) {
                            promise.setFailure(new PException(new ReplicationException(gop.rpc_error.errno)));
                        }
                        else if (gop.get_response().error != 0) {
                            promise.setFailure(new PException("rocksdb error: " + gop.get_response().error));
                        }
                        else {
                            promise.setSuccess(null);
                        }
                    }
                }, timeout);
        return promise;
    }

    @Override
    public Future<Void> asyncSet(byte[] hashKey, byte[] sortKey, byte[] value, int timeout) {
        return asyncSet(hashKey, sortKey, value, 0, timeout);
    }

    private Future<MultiGetResult> asyncMultiGet(byte[] hashKey, List<byte[]> sortKeys,
                                                 int maxFetchCount, int maxFetchSize, boolean noValue, int timeout) {
        final DefaultPromise<MultiGetResult> promise = table.newPromise();
        if (hashKey == null || hashKey.length == 0) {
            promise.setFailure(new PException("Invalid parameter: hashKey should not be null or empty"));
            return promise;
        }
        if (hashKey.length >= 0xFFFF) {
            promise.setFailure(new PException("Invalid parameter: hashKey length should be less than UINT16_MAX"));
            return promise;
        }

        blob hashKeyBlob = new blob(hashKey);
        List<blob> sortKeyBlobs = new ArrayList<blob>();
        Map<ByteBuffer, byte[]> setKeyMap = null;

        if (sortKeys != null && sortKeys.size() > 0) {
            setKeyMap = new TreeMap<ByteBuffer, byte[]>();
            for (int i = 0; i < sortKeys.size(); i++) {
                byte[] sortKey = sortKeys.get(i);
                if (sortKey == null) {
                    promise.setFailure(new PException("Invalid parameter: sortKeys["+i+"] should not be null"));
                    return promise;
                }
                setKeyMap.put(ByteBuffer.wrap(sortKey), sortKey);
            }
            for (Map.Entry<ByteBuffer, byte[]> entry: setKeyMap.entrySet()) {
                sortKeyBlobs.add(new blob(entry.getValue()));
            }
        }

        multi_get_request request = new multi_get_request(
                hashKeyBlob, sortKeyBlobs, maxFetchCount, maxFetchSize, noValue,
                null, null, true, false, filter_type.FT_NO_FILTER, null, false);
        gpid gpid = table.getHashKeyGpid(request.hash_key.data);
        rrdb_multi_get_operator op = new rrdb_multi_get_operator(gpid, table.getTableName(), request);
        final Map<ByteBuffer, byte[]> finalSetKeyMap = setKeyMap;

        table.asyncOperate(
                op,
                new Table.ClientOPCallback() {
                    @Override
                    public void onCompletion(client_operator clientOP) {
                        rrdb_multi_get_operator gop = (rrdb_multi_get_operator) clientOP;
                        if (gop.rpc_error.errno != error_code.error_types.ERR_OK) {
                            promise.setFailure(new PException(new ReplicationException(gop.rpc_error.errno)));
                        }
                        else if (gop.get_response().error != 0 && gop.get_response().error != 7) {
                            // rocksdb::Status::kOk && rocksdb::Status::kIncomplete
                            promise.setFailure(new PException("rocksdb error: " + gop.get_response().error));
                        }
                        else {
                            MultiGetResult result = new MultiGetResult();
                            result.allFetched = (gop.get_response().error == 0);
                            result.values = new ArrayList<Pair<byte[], byte[]>>(gop.get_response().kvs.size());
                            if (finalSetKeyMap == null) {
                                for (key_value kv : gop.get_response().kvs) {
                                    result.values.add(new ImmutablePair<byte[], byte[]>(kv.key.data, kv.value.data));
                                }
                            }
                            else {
                                for (key_value kv : gop.get_response().kvs) {
                                    byte[] sortKey = finalSetKeyMap.get(ByteBuffer.wrap(kv.key.data));
                                    if (sortKey != null) {
                                        result.values.add(new ImmutablePair<byte[], byte[]>(sortKey, kv.value.data));
                                    }
                                }
                            }
                            promise.setSuccess(result);
                        }
                    }
                }, timeout);
        return promise;
    }

    @Override
    public Future<MultiGetResult> asyncMultiGet(byte[] hashKey, List<byte[]> sortKeys,
                                                int maxFetchCount, int maxFetchSize, int timeout) {
        return asyncMultiGet(hashKey, sortKeys, maxFetchCount, maxFetchSize, false, timeout);
    }

    @Override
    public Future<MultiGetResult> asyncMultiGet(byte[] hashKey, List<byte[]> sortKeys, int timeout) {
        return asyncMultiGet(hashKey, sortKeys, 100, 1000000, false, timeout);
    }

    @Override
    public Future<MultiGetResult> asyncMultiGet(byte[] hashKey, byte[] startSortKey, byte[] stopSortKey,
                                                MultiGetOptions options, int maxFetchCount, int maxFetchSize,
                                                int timeout/*ms*/) {
        final DefaultPromise<MultiGetResult> promise = table.newPromise();
        if (hashKey == null || hashKey.length == 0) {
            promise.setFailure(new PException("Invalid parameter: hashKey should not be null or empty"));
            return promise;
        }
        if (hashKey.length >= 0xFFFF) {
            promise.setFailure(new PException("Invalid parameter: hashKey length should be less than UINT16_MAX"));
            return promise;
        }

        blob hashKeyBlob = new blob(hashKey);
        blob startSortKeyBlob = (startSortKey == null ? null : new blob(startSortKey));
        blob stopSortKeyBlob = (stopSortKey == null ? null : new blob(stopSortKey));
        blob sortKeyFilterPatternBlob =
                (options.sortKeyFilterPattern == null ? null : new blob(options.sortKeyFilterPattern));

        multi_get_request request = new multi_get_request(
                hashKeyBlob, null, maxFetchCount, maxFetchSize, options.noValue,
                startSortKeyBlob, stopSortKeyBlob, options.startInclusive, options.stopInclusive,
                filter_type.findByValue(options.sortKeyFilterType.getValue()), sortKeyFilterPatternBlob,
                options.reverse);
        gpid gpid = table.getHashKeyGpid(request.hash_key.data);
        rrdb_multi_get_operator op = new rrdb_multi_get_operator(gpid, table.getTableName(), request);

        table.asyncOperate(
                op,
                new Table.ClientOPCallback() {
                    @Override
                    public void onCompletion(client_operator clientOP) {
                        rrdb_multi_get_operator gop = (rrdb_multi_get_operator) clientOP;
                        if (gop.rpc_error.errno != error_code.error_types.ERR_OK) {
                            promise.setFailure(new PException(new ReplicationException(gop.rpc_error.errno)));
                        }
                        else if (gop.get_response().error != 0 && gop.get_response().error != 7) {
                            // rocksdb::Status::kOk && rocksdb::Status::kIncomplete
                            promise.setFailure(new PException("rocksdb error: " + gop.get_response().error));
                        }
                        else {
                            MultiGetResult result = new MultiGetResult();
                            result.allFetched = (gop.get_response().error == 0);
                            result.values = new ArrayList<Pair<byte[], byte[]>>(gop.get_response().kvs.size());
                            for (key_value kv : gop.get_response().kvs) {
                                result.values.add(new ImmutablePair<byte[], byte[]>(kv.key.data, kv.value.data));
                            }
                            promise.setSuccess(result);
                        }
                    }
                }, timeout);
        return promise;
    }

    @Override
    public Future<MultiGetResult> asyncMultiGet(byte[] hashKey, byte[] startSortKey, byte[] stopSortKey,
                                                MultiGetOptions options, int timeout/*ms*/) {
        return asyncMultiGet(hashKey, startSortKey, stopSortKey, options, 100, 1000000, timeout);
    }

    @Override
    public Future<MultiGetSortKeysResult> asyncMultiGetSortKeys(byte[] hashKey,
                                                                int maxFetchCount, int maxFetchSize, int timeout) {
        final DefaultPromise<MultiGetSortKeysResult> promise = table.newPromise();
        asyncMultiGet(hashKey, null, maxFetchCount, maxFetchSize, true, timeout)
                .addListener(new MultiGetListener() {
            @Override
            public void operationComplete(Future<MultiGetResult> future) throws Exception {
                if (future.isSuccess()) {
                    MultiGetResult result = future.getNow();
                    MultiGetSortKeysResult sortkeyResult = new MultiGetSortKeysResult();
                    sortkeyResult.allFetched = result.allFetched;
                    sortkeyResult.keys = new ArrayList<byte[]>(result.values.size());
                    for (Pair<byte[], byte[]> kv: result.values) {
                        sortkeyResult.keys.add(kv.getLeft());
                    }

                    promise.setSuccess(sortkeyResult);
                }
                else {
                    promise.setFailure(future.cause());
                }
            }
        });

        return promise;
    }

    @Override
    public Future<MultiGetSortKeysResult> asyncMultiGetSortKeys(byte[] hashKey, int timeout) {
        return asyncMultiGetSortKeys(hashKey, 100, 1000000, timeout);
    }

    @Override
    public Future<Void> asyncMultiSet(byte[] hashKey, List<Pair<byte[], byte[]>> values,
                                      int ttlSeconds, int timeout) {
        final DefaultPromise<Void> promise = table.newPromise();
        if (hashKey == null || hashKey.length == 0) {
            promise.setFailure(new PException("Invalid parameter: hashKey should not be null or empty"));
            return promise;
        }
        if (hashKey.length >= 0xFFFF) {
            promise.setFailure(new PException("Invalid parameter: hashKey length should be less than UINT16_MAX"));
            return promise;
        }
        if (values == null || values.size() == 0) {
            promise.setFailure(new PException("Invalid parameter: values should not be null or empty"));
            return promise;
        }

        blob hash_key_blob = new blob(hashKey);
        List<key_value> values_blob = new ArrayList<key_value>();
        for (int i = 0; i < values.size(); i++) {
            byte[] k = values.get(i).getKey();
            if (k == null) {
                promise.setFailure(new PException("Invalid parameter: values["+i+"].key should not be null"));
                return promise;
            }
            byte[] v = values.get(i).getValue();
            if (v == null) {
                promise.setFailure(new PException("Invalid parameter: values["+i+"].value should not be null"));
                return promise;
            }
            values_blob.add(new key_value(new blob(k), new blob(v)));
        }
        int expireTsSseconds = (ttlSeconds == 0 ? 0 : ttlSeconds + (int)tools.epoch_now());
        multi_put_request request = new multi_put_request(hash_key_blob, values_blob, expireTsSseconds);

        gpid gpid = table.getHashKeyGpid(hashKey);
        rrdb_multi_put_operator op = new rrdb_multi_put_operator(gpid, table.getTableName(), request);

        table.asyncOperate(op, new Table.ClientOPCallback() {
            @Override
            public void onCompletion(client_operator clientOP) {
                rrdb_multi_put_operator op2 = (rrdb_multi_put_operator) clientOP;
                if (op2.rpc_error.errno != error_code.error_types.ERR_OK) {
                    promise.setFailure(new PException(new ReplicationException(op2.rpc_error.errno)));
                }
                else if (op2.get_response().error != 0) {
                    promise.setFailure(new PException("rocksdb error: " + op2.get_response().error));
                }
                else {
                    promise.setSuccess(null);
                }
            }
        }, timeout);
        return promise;
    }

    @Override
    public Future<Void> asyncMultiSet(byte[] hashKey, List<Pair<byte[], byte[]>> values, int timeout) {
        return asyncMultiSet(hashKey, values, 0, timeout);
    }

    @Override
    public Future<Void> asyncDel(byte[] hashKey, byte[] sortKey, int timeout) {
        final DefaultPromise<Void> promise = table.newPromise();
        blob request = new blob(PegasusClient.generateKey(hashKey, sortKey));
        gpid gpid = table.getGpid(request.data);
        rrdb_remove_operator op = new rrdb_remove_operator(gpid, table.getTableName(), request);

        table.asyncOperate(op, new Table.ClientOPCallback() {
            @Override
            public void onCompletion(client_operator clientOP) {
                rrdb_remove_operator op2 = (rrdb_remove_operator) clientOP;
                if (op2.rpc_error.errno != error_code.error_types.ERR_OK) {
                    promise.setFailure(new PException(new ReplicationException(op2.rpc_error.errno)));
                }
                else if (op2.get_response().error != 0) {
                    promise.setFailure(new PException("rocksdb error: " + op2.get_response().error));
                }
                else {
                    promise.setSuccess(null);
                }
            }
        }, timeout);
        return promise;
    }

    @Override
    public Future<Void> asyncMultiDel(byte[] hashKey, final List<byte[]> sortKeys, int timeout) {
        final DefaultPromise<Void> promise = table.newPromise();
        if (hashKey == null || hashKey.length == 0) {
            promise.setFailure(new PException("Invalid parameter: hashKey should not be null or empty"));
            return promise;
        }
        if (hashKey.length >= 0xFFFF) {
            promise.setFailure(new PException("Invalid parameter: hashKey length should be less than UINT16_MAX"));
            return promise;
        }
        if (sortKeys == null || sortKeys.isEmpty()) {
            promise.setFailure(new PException("Invalid parameter: sortKeys size should be at lease 1"));
            return promise;
        }

        List<blob> sortKeyBlobs = new ArrayList<blob>(sortKeys.size());
        for (int i = 0; i < sortKeys.size(); i++) {
            byte[] sortKey = sortKeys.get(i);
            if (sortKey == null) {
                promise.setFailure(new PException("Invalid parameter: sortKeys["+i+"] should not be null"));
                return promise;
            }
            sortKeyBlobs.add(new blob(sortKey));
        }
        multi_remove_request request = new multi_remove_request(
                new blob(hashKey),
                sortKeyBlobs,
                100
        );

        gpid pid = table.getHashKeyGpid(hashKey);
        rrdb_multi_remove_operator op = new rrdb_multi_remove_operator(pid, table.getTableName(), request);

        table.asyncOperate(
                op,
                new Table.ClientOPCallback() {
                    public void onCompletion(client_operator clientOP) {
                        rrdb_multi_remove_operator op2 = (rrdb_multi_remove_operator) clientOP;
                        if (op2.rpc_error.errno != error_code.error_types.ERR_OK) {
                            promise.setFailure(new PException(new ReplicationException(op2.rpc_error.errno)));
                        }
                        else if (op2.get_response().error != 0) {
                            promise.setFailure(new PException("rocksdb error: " + op2.get_response().error));
                        }
                        else {
                            Validate.isTrue(op2.get_response().count == sortKeys.size());
                            promise.setSuccess(null);
                        }
                    }
                }, timeout);
        return promise;
    }

    @Override
    public Future<Integer> asyncTTL(byte[] hashKey, byte[] sortKey, int timeout) {
        final DefaultPromise<Integer> promise = table.newPromise();
        blob request = new blob(PegasusClient.generateKey(hashKey, sortKey));

        gpid pid = table.getGpid(request.data);
        rrdb_ttl_operator op = new rrdb_ttl_operator(pid, table.getTableName(), request);

        table.asyncOperate(op, new Table.ClientOPCallback() {
            @Override
            public void onCompletion(client_operator clientOP) {
                rrdb_ttl_operator op2 = (rrdb_ttl_operator) clientOP;
                if (op2.rpc_error.errno != error_code.error_types.ERR_OK) {
                    promise.setFailure(new PException(new ReplicationException(op2.rpc_error.errno)));
                }
                else if (op2.get_response().error != 0 && op2.get_response().error != 1) {
                    promise.setFailure(new PException("rocksdb error: " + op2.get_response().error));
                }
                else {
                    promise.setSuccess( op2.get_response().error == 1 ? -2 : op2.get_response().ttl_seconds );
                }
            }
        }, timeout);
        return promise;
    }

    @Override
    public boolean exist(byte[] hashKey, byte[] sortKey, int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            return asyncExist(hashKey, sortKey, timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public long sortKeyCount(byte[] hashKey, int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            return asyncSortKeyCount(hashKey, timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public byte[] get(byte[] hashKey, byte[] sortKey, int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            return asyncGet(hashKey, sortKey, timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public void batchGet(List<Pair<byte[], byte[]>> keys, List<byte[]> values, int timeout) throws PException {
        if (keys == null || keys.size() == 0) {
            throw new PException("Invalid parameter: keys should not be null or empty");
        }
        if (values == null) {
            throw new PException("Invalid parameter: values should not be null");
        }
        values.clear();
        List<Future<byte[]>> futures = new ArrayList<Future<byte[]>>();
        for (Pair<byte[], byte[]> k : keys) {
            values.add(null);
            futures.add(asyncGet(k.getLeft(), k.getRight(), timeout));
        }
        for (int i = 0; i < keys.size(); i++) {
            Future<byte[]> fu = futures.get(i);
            fu.awaitUninterruptibly();
            if (fu.isSuccess()) {
                values.set(i, fu.getNow());
            } else {
                Throwable cause = fu.cause();
                throw new PException("Get value of keys[" + i + "] failed: " + cause.getMessage(), cause);
            }
        }
    }

    @Override
    public int batchGet2(List<Pair<byte[], byte[]>> keys,
                         List<Pair<PException, byte[]>> results, int timeout) throws PException {
        if (keys == null || keys.size() == 0) {
            throw new PException("Invalid parameter: keys should not be null or empty");
        }
        if (results == null) {
            throw new PException("Invalid parameter: results should not be null");
        }
        results.clear();
        List<Future<byte[]>> futures = new ArrayList<Future<byte[]>>();
        for (Pair<byte[], byte[]> k : keys) {
            futures.add(asyncGet(k.getLeft(), k.getRight(), timeout));
        }
        int count = 0;
        PException nullEx = null;
        byte[] nullBytes = null;
        for (int i = 0; i < keys.size(); i++) {
            Future<byte[]> fu = futures.get(i);
            fu.awaitUninterruptibly();
            if (fu.isSuccess()) {
                results.add(Pair.of(nullEx, fu.getNow()));
                count++;
            } else {
                Throwable cause = fu.cause();
                results.add(Pair.of(
                        new PException("Get value of keys[" + i + "] failed: " + cause.getMessage(), cause),
                        nullBytes
                ));
            }
        }
        return count;
    }

    @Override
    public MultiGetResult multiGet(byte[] hashKey, List<byte[]> sortKeys,
                                   int maxFetchCount, int maxFetchSize, int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            return asyncMultiGet(hashKey, sortKeys, maxFetchCount, maxFetchSize, timeout)
                    .get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public MultiGetResult multiGet(byte[] hashKey, List<byte[]> sortKeys, int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            return asyncMultiGet(hashKey, sortKeys, timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public MultiGetResult multiGet(byte[] hashKey, byte[] startSortKey, byte[] stopSortKey,
                                   MultiGetOptions options, int maxFetchCount, int maxFetchSize,
                                   int timeout/*ms*/) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            return asyncMultiGet(hashKey, startSortKey, stopSortKey, options,
                    maxFetchCount, maxFetchSize, timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public MultiGetResult multiGet(byte[] hashKey, byte[] startSortKey, byte[] stopSortKey,
                                   MultiGetOptions options, int timeout/*ms*/) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            return asyncMultiGet(hashKey, startSortKey, stopSortKey, options,
                    timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public void batchMultiGet(List<Pair<byte[], List<byte[]>>> keys,
                              List<HashKeyData> values, int timeout) throws PException {
        if (keys == null || keys.size() == 0) {
            throw new PException("Invalid parameter: keys should not be null or empty");
        }
        if (values == null) {
            throw new PException("Invalid parameter: values should not be null");
        }
        values.clear();
        List<Future<MultiGetResult>> futures = new ArrayList<Future<MultiGetResult>>();
        for (Pair<byte[], List<byte[]>> k : keys) {
            values.add(null);
            futures.add(asyncMultiGet(k.getLeft(), k.getRight(), timeout));
        }
        for (int i = 0; i < keys.size(); i++) {
            Future<MultiGetResult> fu = futures.get(i);
            fu.awaitUninterruptibly();
            if (fu.isSuccess()) {
                values.set(i, new HashKeyData(keys.get(i).getLeft(), fu.getNow().values));
            } else {
                Throwable cause = fu.cause();
                throw new PException("MultiGet values of keys[" + i + "] failed: " + cause.getMessage(), cause);
            }
        }
    }

    @Override
    public int batchMultiGet2(List<Pair<byte[], List<byte[]>>> keys,
                              List<Pair<PException, HashKeyData>> results, int timeout) throws PException {
        if (keys == null || keys.size() == 0) {
            throw new PException("Invalid parameter: keys should not be null or empty");
        }
        if (results == null) {
            throw new PException("Invalid parameter: results should not be null");
        }
        results.clear();
        List<Future<MultiGetResult>> futures = new ArrayList<Future<MultiGetResult>>();
        for (Pair<byte[], List<byte[]>> k : keys) {
            futures.add(asyncMultiGet(k.getLeft(), k.getRight(), timeout));
        }
        int count = 0;
        PException nullEx = null;
        HashKeyData nullData = null;
        for (int i = 0; i < keys.size(); i++) {
            Future<MultiGetResult> fu = futures.get(i);
            fu.awaitUninterruptibly();
            if (fu.isSuccess()) {
                results.add(Pair.of(
                        nullEx,
                        new HashKeyData(keys.get(i).getLeft(), fu.getNow().values)
                ));
                count++;
            } else {
                Throwable cause = fu.cause();
                results.add(Pair.of(
                        new PException("MultiGet value of keys[" + i + "] failed: " + cause.getMessage(), cause),
                        nullData
                ));
            }
        }
        return count;
    }

    @Override
    public MultiGetSortKeysResult multiGetSortKeys(byte[] hashKey, int maxFetchCount, int maxFetchSize,
                                                   int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            return asyncMultiGetSortKeys(hashKey, maxFetchCount, maxFetchSize, timeout)
                    .get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public MultiGetSortKeysResult multiGetSortKeys(byte[] hashKey, int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            return asyncMultiGetSortKeys(hashKey, timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public void set(byte[] hashKey, byte[] sortKey, byte[] value, int ttlSeconds, int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            asyncSet(hashKey, sortKey, value, ttlSeconds, timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public void set(byte[] hashKey, byte[] sortKey, byte[] value, int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            asyncSet(hashKey, sortKey, value, timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public void batchSet(List<SetItem> items, int timeout) throws PException {
        if (items == null) {
            throw new PException("Invalid parameter: items should not be null");
        }
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        for (SetItem i : items) {
            futures.add(asyncSet(i.hashKey, i.sortKey, i.value, i.ttlSeconds, timeout));
        }
        for (int i = 0; i < items.size(); i++) {
            Future<Void> fu = futures.get(i);
            fu.awaitUninterruptibly();
            if (!fu.isSuccess()) {
                Throwable cause = fu.cause();
                throw new PException("Set value of items[" + i + "] failed: " + cause.getMessage(), cause);
            }
        }
    }

    @Override
    public int batchSet2(List<SetItem> items, List<PException> results, int timeout) throws PException {
        if (items == null) {
            throw new PException("Invalid parameter: items should not be null");
        }
        if (results == null) {
            throw new PException("Invalid parameter: results should not be null");
        }
        results.clear();
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        for (SetItem i : items) {
            futures.add(asyncSet(i.hashKey, i.sortKey, i.value, i.ttlSeconds, timeout));
        }
        int count = 0;
        PException nullEx = null;
        for (int i = 0; i < items.size(); i++) {
            Future<Void> fu = futures.get(i);
            fu.awaitUninterruptibly();
            if (fu.isSuccess()) {
                results.add(nullEx);
                count++;
            } else {
                Throwable cause = fu.cause();
                results.add(new PException("Set value of items[" + i + "] failed: " + cause.getMessage(), cause));
            }
        }
        return count;
    }

    @Override
    public void multiSet(byte[] hashKey, List<Pair<byte[], byte[]>> values,
                         int ttlSeconds, int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            asyncMultiSet(hashKey, values, ttlSeconds, timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public void multiSet(byte[] hashKey, List<Pair<byte[], byte[]>> values, int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            asyncMultiSet(hashKey, values, timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public void batchMultiSet(List<HashKeyData> items, int ttl_seconds, int timeout) throws PException {
        if (items == null || items.size() == 0) {
            throw new PException("Invalid parameter: items should not be null or empty");
        }
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        for (HashKeyData item : items) {
            futures.add(asyncMultiSet(item.hashKey, item.values, ttl_seconds, timeout));
        }
        for (int i = 0; i < items.size(); i++) {
            Future<Void> fu = futures.get(i);
            fu.awaitUninterruptibly();
            if (!fu.isSuccess()) {
                Throwable cause = fu.cause();
                throw new PException("MultiSet values of items[" + i + "] failed: " + cause.getMessage(), cause);
            }
        }
    }

    @Override
    public int batchMultiSet2(List<HashKeyData> items,
                              int ttl_seconds, List<PException> results, int timeout) throws PException {
        if (items == null) {
            throw new PException("Invalid parameter: items should not be null");
        }
        if (results == null) {
            throw new PException("Invalid parameter: results should not be null");
        }
        results.clear();
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        for (HashKeyData item : items) {
            futures.add(asyncMultiSet(item.hashKey, item.values, ttl_seconds, timeout));
        }
        int count = 0;
        PException nullEx = null;
        for (int i = 0; i < items.size(); i++) {
            Future<Void> fu = futures.get(i);
            fu.awaitUninterruptibly();
            if (fu.isSuccess()) {
                results.add(nullEx);
                count++;
            } else {
                Throwable cause = fu.cause();
                results.add(new PException("MultiSet value of items[" + i + "] failed: " + cause.getMessage(), cause));
            }
        }
        return count;

    }

    @Override
    public void del(byte[] hashKey, byte[] sortKey, int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            asyncDel(hashKey, sortKey, timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public void batchDel(List<Pair<byte[], byte[]>> keys, int timeout) throws PException {
        if (keys == null || keys.size() == 0) {
            throw new PException("Invalid parameter: keys should not be null or empty");
        }
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        for (Pair<byte[], byte[]> k : keys) {
            futures.add(asyncDel(k.getLeft(), k.getRight(), timeout));
        }
        for (int i = 0; i < keys.size(); i++) {
            Future<Void> fu = futures.get(i);
            fu.awaitUninterruptibly();
            if (!fu.isSuccess()) {
                Throwable cause = fu.cause();
                throw new PException("Del value of keys[" + i + "] failed: " + cause.getMessage(), cause);
            }
        }
    }

    @Override
    public int batchDel2(List<Pair<byte[], byte[]>> keys,
                         List<PException> results, int timeout) throws PException {
        if (keys == null) {
            throw new PException("Invalid parameter: keys should not be null");
        }
        if (results == null) {
            throw new PException("Invalid parameter: results should not be null");
        }
        results.clear();
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        for (Pair<byte[], byte[]> k : keys) {
            futures.add(asyncDel(k.getLeft(), k.getRight(), timeout));
        }
        int count = 0;
        PException nullEx = null;
        for (int i = 0; i < keys.size(); i++) {
            Future<Void> fu = futures.get(i);
            fu.awaitUninterruptibly();
            if (fu.isSuccess()) {
                results.add(nullEx);
                count++;
            } else {
                Throwable cause = fu.cause();
                results.add(new PException("Del value of keys[" + i + "] failed: " + cause.getMessage(), cause));
            }
        }
        return count;
    }

    @Override
    public void multiDel(byte[] hashKey, List<byte[]> sortKeys, int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            asyncMultiDel(hashKey, sortKeys, timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public void batchMultiDel(List<Pair<byte[], List<byte[]>>> keys, int timeout) throws PException {
        if (keys == null || keys.size() == 0) {
            throw new PException("Invalid parameter: keys should not be null or empty");
        }
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        for (Pair<byte[], List<byte[]>> k : keys) {
            futures.add(asyncMultiDel(k.getLeft(), k.getRight(), timeout));
        }
        for (int i = 0; i < keys.size(); i++) {
            Future<Void> fu = futures.get(i);
            fu.awaitUninterruptibly();
            if (!fu.isSuccess()) {
                Throwable cause = fu.cause();
                throw new PException("MultiDel values of keys[" + i + "] failed: " + cause.getMessage(), cause);
            }
        }
    }

    @Override
    public int batchMultiDel2(List<Pair<byte[], List<byte[]>>> keys,
                              List<PException> results, int timeout) throws PException {
        if (keys == null) {
            throw new PException("Invalid parameter: keys should not be null");
        }
        if (results == null) {
            throw new PException("Invalid parameter: results should not be null");
        }
        results.clear();
        List<Future<Void>> futures = new ArrayList<Future<Void>>();
        for (Pair<byte[], List<byte[]>> k : keys) {
            futures.add(asyncMultiDel(k.getLeft(), k.getRight(), timeout));
        }
        int count = 0;
        PException nullEx = null;
        for (int i = 0; i < keys.size(); i++) {
            Future<Void> fu = futures.get(i);
            fu.awaitUninterruptibly();
            if (fu.isSuccess()) {
                results.add(nullEx);
                count++;
            } else {
                Throwable cause = fu.cause();
                results.add(new PException("MultiDel value of keys[" + i + "] failed: " + cause.getMessage(), cause));
            }
        }
        return count;
    }

    @Override
    public int ttl(byte[] hashKey, byte[] sortKey, int timeout) throws PException {
        if (timeout <= 0)
            timeout = defaultTimeout;
        try {
            return asyncTTL(hashKey, sortKey, timeout).get(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (TimeoutException e) {
            throw new PException(new ReplicationException(error_code.error_types.ERR_TIMEOUT));
        } catch (ExecutionException e) {
            throw new PException(e);
        }
    }

    @Override
    public PegasusScannerInterface getScanner(byte[] hashKey, byte[] startSortKey, byte[] stopSortKey,
                                              ScanOptions options) throws PException {
        if (hashKey == null || hashKey.length == 0) {
            throw new PException("Invalid parameter: hash key can't be empty");
        }
        if (options.timeoutMillis <= 0) {
            options.timeoutMillis = defaultTimeout;
        }

        ScanOptions o = new ScanOptions(options);

        // generate key range by start_sort_key and stop_sort_key
        byte[] start = PegasusClient.generateKey(hashKey, startSortKey);
        byte[] stop;
        if (stopSortKey == null || stopSortKey.length == 0) {
            stop = PegasusClient.generateNextBytes(hashKey);
            o.stopInclusive = false;
        } else {
            stop = PegasusClient.generateKey(hashKey, stopSortKey);
        }

        // limit key range by prefix filter
        if (o.sortKeyFilterType == FilterType.FT_MATCH_PREFIX
                && o.sortKeyFilterPattern != null && o.sortKeyFilterPattern.length > 0) {
            byte[] prefix_start = PegasusClient.generateKey(hashKey, o.sortKeyFilterPattern);
            if (PegasusClient.bytesCompare(prefix_start, start) > 0) {
                start = prefix_start;
                o.startInclusive = true;
            }
            byte[] prefix_stop = PegasusClient.generateNextBytes(hashKey, o.sortKeyFilterPattern);
            if (PegasusClient.bytesCompare(prefix_stop, stop) <= 0) {
                stop = prefix_stop;
                o.stopInclusive = false;
            }
        }

        // check if range is empty
        int cmp = PegasusClient.bytesCompare(start, stop);
        gpid[] v = cmp < 0 || cmp == 0 && o.startInclusive && o.stopInclusive ?
                // (start < stop) or (start == stop and bounds are inclusive)
                new gpid[]{ table.getGpid(start) } :
                new gpid[0];

        return new PegasusScanner(table, v, o, new blob(start), new blob(stop));
    }

    @Override
    public List<PegasusScannerInterface> getUnorderedScanners(int maxSplitCount,
                                                              ScanOptions options) throws PException {
        if (maxSplitCount <= 0) {
            throw new PException("Invalid parameter: the max count of splits must be greater than 0");
        }
        if (options.timeoutMillis <= 0) {
            options.timeoutMillis = defaultTimeout;
        }

        gpid[] all = table.getAllGpid();
        int count = all.length;
        int split = count < maxSplitCount ? count : maxSplitCount;
        List<PegasusScannerInterface> ret = new ArrayList<PegasusScannerInterface>(split);

        int size = count / split;
        int more = count - size * split;

        //use default value for other fields in scan_options
        ScanOptions opt = new ScanOptions();
        opt.timeoutMillis = options.timeoutMillis;
        opt.batchSize = options.batchSize;
        opt.noValue = options.noValue;
        opt.sortKeyFilterType = options.sortKeyFilterType;
        opt.sortKeyFilterPattern = options.sortKeyFilterPattern;
        for (int i = 0; i < split; i++) {
            int s = i < more ? size + 1 : size;
            gpid[] v = new gpid[s];
            for (int j = 0; j < s; j++)
                v[j] = all[--count];
            ret.add(new PegasusScanner(table, v, opt));
        }
        return ret;
    }
}
