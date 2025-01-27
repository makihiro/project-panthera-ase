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
package org.apache.hadoop.hive.ql.parse.sql.generator;

import org.antlr33.runtime.tree.CommonTree;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.apache.hadoop.hive.ql.parse.sql.TranslateContext;

import br.com.porcelli.parser.plsql.PantheraParser_PLSQLParser;

public class TableRefElementGenerator extends BaseHiveASTGenerator implements HiveASTGenerator {

  @Override
  public boolean generate(ASTNode hiveRoot, CommonTree sqlRoot, ASTNode currentHiveNode,
      CommonTree currentSqlNode, TranslateContext context) throws Exception {

    ASTNode trc;
    if ((currentSqlNode.getChildCount() == 1 ? currentSqlNode.getChild(0).getChild(0).getType()
        : currentSqlNode
            .getChild(1).getChild(0).getType()) == PantheraParser_PLSQLParser.SELECT_MODE) {// otherwise
                                                                                            // DIRECT_MODE
      trc = super.newHiveASTNode(HiveParser.TOK_SUBQUERY, "TOK_SUBQUERY");
    } else {
      trc = super.newHiveASTNode(HiveParser.TOK_TABREF, "TOK_TABREF");
    }
    super.attachHiveNode(hiveRoot, currentHiveNode, trc);
    currentHiveNode=trc;
    if (!super.generateChildren(hiveRoot, sqlRoot, currentHiveNode, currentSqlNode, context)) {
      return false;
    }
    super.exchangeChildrenPosition(currentHiveNode);// for alias
    return true;
  }

}
