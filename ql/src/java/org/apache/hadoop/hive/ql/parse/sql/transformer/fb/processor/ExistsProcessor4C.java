/*
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
package org.apache.hadoop.hive.ql.parse.sql.transformer.fb.processor;

import org.apache.hadoop.hive.ql.parse.sql.PantheraExpParser;
import org.apache.hadoop.hive.ql.parse.sql.SqlXlateException;
import org.apache.hadoop.hive.ql.parse.sql.transformer.fb.FilterBlockUtil;

import br.com.porcelli.parser.plsql.PantheraParser_PLSQLParser;
/**
 * correlated exists processor
 * ExistsProcessor4C.
 *
 */
public class ExistsProcessor4C extends CommonFilterBlockProcessor {

  @Override
  void processFB() throws SqlXlateException {
    boolean isNot = super.subQNode.getParent().getType() == PantheraParser_PLSQLParser.SQL92_RESERVED_NOT ? true
        : false;
    if (isNot) {
      super.processNotExistsCByRightOuterJoin(FilterBlockUtil.createSqlASTNode(PantheraExpParser.CROSS_VK, "leftsemi"));
    } else {
      super.processExistsC(FilterBlockUtil.createSqlASTNode(PantheraExpParser.CROSS_VK, "leftsemi"));
    }
  }

}
