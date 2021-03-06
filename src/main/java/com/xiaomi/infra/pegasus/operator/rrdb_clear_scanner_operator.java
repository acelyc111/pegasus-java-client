// Copyright (c) 2017, Xiaomi, Inc.  All rights reserved.
// This source code is licensed under the Apache License Version 2.0, which
// can be found in the LICENSE file in the root directory of this source tree.
package com.xiaomi.infra.pegasus.operator;

import com.xiaomi.infra.pegasus.apps.scan_response;
import com.xiaomi.infra.pegasus.thrift.TException;
import com.xiaomi.infra.pegasus.thrift.protocol.TMessage;
import com.xiaomi.infra.pegasus.thrift.protocol.TMessageType;
import com.xiaomi.infra.pegasus.thrift.protocol.TProtocol;

import com.xiaomi.infra.pegasus.apps.rrdb;

public class rrdb_clear_scanner_operator extends client_operator {
  public rrdb_clear_scanner_operator(com.xiaomi.infra.pegasus.base.gpid gpid, String tableName, long request) {
    super(gpid, tableName);
    this.request = request;
  }

  public String name() { return "clear_scanner"; }
  public void send_data(com.xiaomi.infra.pegasus.thrift.protocol.TProtocol oprot, int seqid) throws TException {
    TMessage msg = new TMessage("RPC_RRDB_RRDB_CLEAR_SCANNER", TMessageType.CALL, seqid);
    oprot.writeMessageBegin(msg);
    rrdb.clear_scanner_args args = new rrdb.clear_scanner_args(request);
    args.write(oprot);
    oprot.writeMessageEnd();
  }

  public void recv_data(TProtocol iprot) throws TException {
  }

  public scan_response get_response() { return null; }

  private long request;
}
