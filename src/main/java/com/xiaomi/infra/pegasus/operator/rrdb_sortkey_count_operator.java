// Copyright (c) 2017, Xiaomi, Inc.  All rights reserved.
// This source code is licensed under the Apache License Version 2.0, which
// can be found in the LICENSE file in the root directory of this source tree.
package com.xiaomi.infra.pegasus.operator;

import com.xiaomi.infra.pegasus.apps.count_response;
import com.xiaomi.infra.pegasus.apps.rrdb;
import com.xiaomi.infra.pegasus.thrift.TException;
import com.xiaomi.infra.pegasus.thrift.protocol.TMessage;
import com.xiaomi.infra.pegasus.thrift.protocol.TMessageType;

/**
 * Created by weijiesun on 16-12-8.
 */
public class rrdb_sortkey_count_operator extends client_operator {
    public rrdb_sortkey_count_operator(com.xiaomi.infra.pegasus.base.gpid gpid, String tableName, com.xiaomi.infra.pegasus.base.blob request) {
        super(gpid, tableName);
        this.request = request;
    }

    public String name() { return "sortkey_count"; }
    public void send_data(com.xiaomi.infra.pegasus.thrift.protocol.TProtocol oprot, int seqid) throws TException {
        TMessage msg = new TMessage("RPC_RRDB_RRDB_SORTKEY_COUNT", TMessageType.CALL, seqid);
        oprot.writeMessageBegin(msg);
        rrdb.sortkey_count_args get_args = new rrdb.sortkey_count_args(request);
        get_args.write(oprot);
        oprot.writeMessageEnd();
    }

    public void recv_data(com.xiaomi.infra.pegasus.thrift.protocol.TProtocol iprot) throws TException {
        rrdb.sortkey_count_result result = new rrdb.sortkey_count_result();
        result.read(iprot);
        if (result.isSetSuccess())
            resp = result.success;
        else
            throw new com.xiaomi.infra.pegasus.thrift.TApplicationException(
                    com.xiaomi.infra.pegasus.thrift.TApplicationException.MISSING_RESULT, "multi get failed: unknown result");
    }

    public count_response get_response() { return resp; }

    private com.xiaomi.infra.pegasus.base.blob request;
    private count_response resp;

}
