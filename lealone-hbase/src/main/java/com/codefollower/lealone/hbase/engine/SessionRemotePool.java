/*
 * Copyright 2011 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codefollower.lealone.hbase.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.codefollower.lealone.command.CommandRemote;
import com.codefollower.lealone.engine.ConnectionInfo;
import com.codefollower.lealone.engine.SessionRemote;
import com.codefollower.lealone.expression.Parameter;
import com.codefollower.lealone.expression.ParameterInterface;
import com.codefollower.lealone.hbase.util.HBaseUtils;
import com.codefollower.lealone.transaction.Transaction;
import com.codefollower.lealone.util.New;

public class SessionRemotePool {
    private static final Map<String, SessionRemote> sessionRemoteCache = New.hashMap();
    private static final Map<String, SessionRemote> sessionRemoteCacheMaybeWithMaster = New.hashMap();

    private static SessionRemote masterSessionRemote;

    public static void addSessionRemote(String url, SessionRemote sessionRemote, boolean isMaster) {
        sessionRemoteCacheMaybeWithMaster.put(url, sessionRemote);
        if (!isMaster)
            sessionRemoteCache.put(url, sessionRemote);
    }

    public static SessionRemote getSessionRemote(String url) {
        return sessionRemoteCacheMaybeWithMaster.get(url);
    }

    public static SessionRemote getSessionRemote(Properties info, String url) {
        Properties prop = new Properties();
        for (String key : info.stringPropertyNames())
            prop.setProperty(key, info.getProperty(key));
        ConnectionInfo ci = new ConnectionInfo(url, prop);

        return (SessionRemote) new SessionRemote(ci).connectEmbeddedOrServer(false);
    }

    public static SessionRemote getMasterSessionRemote(Properties info) {
        if (masterSessionRemote == null || masterSessionRemote.isClosed()) {
            synchronized (SessionRemotePool.class) {
                if (masterSessionRemote == null || masterSessionRemote.isClosed()) {
                    masterSessionRemote = getSessionRemote(info, HBaseUtils.getMasterURL());
                }
            }
        }
        return masterSessionRemote;
    }

    public static CommandRemote getMasterCommandRemote(Properties info, String sql, List<Parameter> parameters) {
        return getCommandRemote(getMasterSessionRemote(info), sql, parameters);
    }

    public static CommandRemote getCommandRemote(HBaseSession originalSession, List<Parameter> parameters, //
            String url, String sql) throws Exception {
        SessionRemote sessionRemote = originalSession.getSessionRemote(url);
        if (sessionRemote != null && sessionRemote.isClosed())
            sessionRemote = null;
        boolean isNew = false;
        if (sessionRemote == null) {
            isNew = true;
            sessionRemote = getSessionRemote(originalSession.getOriginalProperties(), url);
        }

        if (sessionRemote.getTransaction() == null) {
            Transaction t = new Transaction();
            t.setAutoCommit(originalSession.getAutoCommit());
            sessionRemote.setTransaction(t);
        }

        if (isNew)
            originalSession.addSessionRemote(url, sessionRemote, false);

        return getCommandRemote(sessionRemote, sql, parameters);
    }

    private static CommandRemote getCommandRemote(SessionRemote sessionRemote, String sql, List<Parameter> parameters) {
        CommandRemote commandRemote = (CommandRemote) sessionRemote.prepareCommand(sql, -1); //此时fetchSize还未知

        //传递最初的参数值到新的CommandRemote
        if (parameters != null) {
            ArrayList<? extends ParameterInterface> newParams = commandRemote.getParameters();
            for (int i = 0, size = parameters.size(); i < size; i++) {
                newParams.get(i).setValue(parameters.get(i).getParamValue(), true);
            }
        }

        return commandRemote;
    }
}
