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
package org.apache.hadoop.hive.ql.parse.sql.transformer.fb;

import org.antlr33.runtime.tree.CommonTree;
import org.apache.hadoop.hive.ql.parse.sql.PantheraExpParser;
import org.apache.hadoop.hive.ql.parse.sql.SqlXlateException;
import org.apache.hadoop.hive.ql.parse.sql.TranslateContext;
import org.apache.hadoop.hive.ql.parse.sql.transformer.fb.processor.FilterBlockProcessorFactory;


public class UnCorrelatedFilterBlock extends NormalFilterBlock {

  @Override
  public void process(FilterBlockContext fbContext, TranslateContext context)
      throws SqlXlateException {

    // If SubQFB is empty and QueryBlock stack only has one element, it ¡s the outer-most query
    // TODO I forget something.
    if ((fbContext.getSubQStack().size() == 0)
        && (fbContext.getQueryStack().size() == 1)) {

//       FilterBlockProcessorFactory.getSimpleProcessor().process(fbContext, this, context);


      CommonTree topSelect = fbContext.getQueryStack().peek().cloneSimpleQuery();
      CommonTree condition = this.getASTNode();

      CommonTree where = FilterBlockUtil.createSqlASTNode(PantheraExpParser.SQL92_RESERVED_WHERE, "where");
      topSelect.addChild(where);
      CommonTree logicExpr = FilterBlockUtil.createSqlASTNode(PantheraExpParser.LOGIC_EXPR,
          "LOGIC_EXPR");
      where.addChild(logicExpr);
      logicExpr.addChild(condition);

      this.setTransformedNode(topSelect);
      return;
    }
    FilterBlockProcessorFactory.getUnCorrelatedProcessor(
        fbContext.getSubQStack().peek().getASTNode()).process(fbContext, this, context);
//    super.processStackSubq(fbContext, context);
  }


}
