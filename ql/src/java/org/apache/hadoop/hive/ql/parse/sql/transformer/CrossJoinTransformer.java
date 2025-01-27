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
package org.apache.hadoop.hive.ql.parse.sql.transformer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.antlr33.runtime.tree.CommonTree;
import org.antlr33.runtime.tree.Tree;
import org.apache.hadoop.hive.ql.parse.sql.PantheraExpParser;
import org.apache.hadoop.hive.ql.parse.sql.SqlASTNode;
import org.apache.hadoop.hive.ql.parse.sql.SqlXlateException;
import org.apache.hadoop.hive.ql.parse.sql.SqlXlateUtil;
import org.apache.hadoop.hive.ql.parse.sql.TranslateContext;
import org.apache.hadoop.hive.ql.parse.sql.transformer.QueryInfo.Column;
import org.apache.hadoop.hive.ql.parse.sql.transformer.fb.FilterBlockUtil;

import br.com.porcelli.parser.plsql.PantheraParser_PLSQLParser;

/**
 * Transformer for multiple-table select.
 *
 */
public class CrossJoinTransformer extends BaseSqlASTTransformer {
  SqlASTTransformer tf;

  private static class JoinPair<T> {
    private final T first;
    private final T second;

    public JoinPair(T first, T second) {
      this.first = first;
      this.second = second;
    }

    public T getFirst() {
      return first;
    }

    public T getSecond() {
      return second;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof JoinPair<?>)) {
        return false;
      }
      JoinPair<T> otherPair = (JoinPair<T>) other;
      return (first.equals(otherPair.first) && second.equals(otherPair.second)) ||
          (first.equals(otherPair.second) && second.equals(otherPair.first));
    }

    @Override
    public int hashCode() {
      return first.hashCode() ^ second.hashCode();
    }
  }

  private class JoinInfo {
    // we use insertion-ordered LinkedHashMap so that table join order honors the order in the where
    // clause.
    public Map<JoinPair<String>, List<CommonTree>> joinPairInfo = new LinkedHashMap<JoinPair<String>, List<CommonTree>>();
    public Map<String, List<CommonTree>> joinFilterInfo = new HashMap<String, List<CommonTree>>();
  }

  public CrossJoinTransformer(SqlASTTransformer tf) {
    this.tf = tf;
  }

  @Override
  public void transform(SqlASTNode tree, TranslateContext context) throws SqlXlateException {
    tf.transformAST(tree, context);
    for (QueryInfo qf : context.getqInfoList()) {
      transformQuery(context, qf, qf.getSelectKeyForThisQ());
      // Update the from in the query info in case it was changed by the transformer.
      qf.setFrom((CommonTree) qf.getSelectKeyForThisQ().getFirstChildWithType(
          PantheraParser_PLSQLParser.SQL92_RESERVED_FROM));
    }
  }

  private void transformQuery(TranslateContext context, QueryInfo qf, CommonTree node) throws SqlXlateException {
    if (node.getType() == PantheraParser_PLSQLParser.SQL92_RESERVED_SELECT) {
      CommonTree from = (CommonTree) node
          .getFirstChildWithType(PantheraParser_PLSQLParser.SQL92_RESERVED_FROM);
      assert (from.getChildCount() == 1);

      // Skip if there is no join operation in the from clause.
      if (((CommonTree) from.getChild(0))
          .getFirstChildWithType(PantheraParser_PLSQLParser.JOIN_DEF) != null) {
        JoinInfo joinInfo = new JoinInfo();

        //
        // Transform the where condition and generate the join operation info.
        //
        CommonTree where = (CommonTree) node
            .getFirstChildWithType(PantheraParser_PLSQLParser.SQL92_RESERVED_WHERE);
        if (where != null) {
          transformWhereCondition(context, qf, (CommonTree) where.getChild(0).getChild(0), joinInfo);
        }
        //
        // Transform the from clause tree using the generated join operation info.
        //
        transformFromClause(qf, from, joinInfo);
      }
    }

    //
    // Transform subqueries in this query.
    //
    for (int i = 0; i < node.getChildCount(); i++) {
      CommonTree child = (CommonTree) node.getChild(i);
      if (child.getType() != PantheraParser_PLSQLParser.SQL92_RESERVED_FROM) {
        transformQuery(context, qf, child);
      }
    }
  }

  private void transformWhereCondition(TranslateContext context, QueryInfo qf, CommonTree node, JoinInfo joinInfo)
      throws SqlXlateException {
    //
    // We can only transform equality expression between two columns whose ancesotors are all AND
    // operators
    // into JOIN on ...
    //
    if (node.getType() == PantheraParser_PLSQLParser.SQL92_RESERVED_AND) {
      transformWhereCondition(context, qf, (CommonTree) node.getChild(0), joinInfo); // Transform the left
                                                                            // child.
      transformWhereCondition(context, qf, (CommonTree) node.getChild(1), joinInfo); // Transform the right
                                                                            // child.

      CommonTree leftChild = (CommonTree) node.getChild(0);
      CommonTree rightChild = (CommonTree) node.getChild(1);

      if (leftChild.getType() == PantheraParser_PLSQLParser.SQL92_RESERVED_TRUE) {
        //
        // Replace the current node with the right child.
        //
        node.getParent().setChild(node.getChildIndex(), rightChild);
      } else if (rightChild.getType() == PantheraParser_PLSQLParser.SQL92_RESERVED_TRUE) {
        //
        // Replace the current node with the left child.
        //
        node.getParent().setChild(node.getChildIndex(), leftChild);
      }
    } else {
      if (node.getType() == PantheraParser_PLSQLParser.EQUALS_OP) {
        //
        // Check if this is a equality expression between two columns
        //
        if (IsColumnRef(node.getChild(0)) && IsColumnRef(node.getChild(1))) {
          String table1 = getTableName(qf, (CommonTree) node.getChild(0).getChild(0));
          String table2 = getTableName(qf, (CommonTree) node.getChild(1).getChild(0));
          //
          // Skip columns not in a src table.
          //
          if (table1 == null || table2 == null) {
            return;
          }
          //
          // Update join info.
          //
          JoinPair<String> tableJoinPair = new JoinPair<String>(table1, table2);
          List<CommonTree> joinEqualityNodes = joinInfo.joinPairInfo.get(tableJoinPair);
          if (joinEqualityNodes == null) {
            joinEqualityNodes = new ArrayList<CommonTree>();
          }
          joinEqualityNodes.add(node);
          joinInfo.joinPairInfo.put(tableJoinPair, joinEqualityNodes);

          //
          // Create a new TRUE node and replace the current node with this new node.
          //
          SqlASTNode trueNode = SqlXlateUtil.newSqlASTNode(
              PantheraParser_PLSQLParser.SQL92_RESERVED_TRUE, "true");
          node.getParent().setChild(node.getChildIndex(), trueNode);
          return;
        }
      }

      // If there is a hint for keeping the node in the where clause, then skip it
      if (context.getBallFromBuskate(node) != null) {
        return;
      }

      //
      // For a where condition that refers any columns from a single table and no subquery, then it
      // can be a join filter.
      //
      List<CommonTree> anyElementList = new ArrayList<CommonTree>();
      FilterBlockUtil.findNode(node, PantheraParser_PLSQLParser.ANY_ELEMENT, anyElementList);

      Set<String> referencedTables = new HashSet<String>();
      String srcTable;
      for (CommonTree anyElement : anyElementList) {
        srcTable = getTableName(qf, (CommonTree) anyElement);
        if (srcTable != null) {
          referencedTables.add(srcTable);
        } else {
          // If the condition refers to a table which is not in the from clause or refers to a column
          // which is not existing, then skip this condition.
          return;
        }
      }

      if (referencedTables.size() == 1
          && !SqlXlateUtil
              .hasNodeTypeInTree(node, PantheraParser_PLSQLParser.SQL92_RESERVED_SELECT)) {
        srcTable = (String) referencedTables.toArray()[0];

        //
        // Update join info.
        //
        List<CommonTree> joinFilterNodes = joinInfo.joinFilterInfo.get(srcTable);
        if (joinFilterNodes == null) {
          joinFilterNodes = new ArrayList<CommonTree>();
        }
        joinFilterNodes.add(node);
        joinInfo.joinFilterInfo.put(srcTable, joinFilterNodes);
        //
        // Create a new TRUE node and replace the current node with this new node.
        //
        SqlASTNode trueNode = SqlXlateUtil.newSqlASTNode(
            PantheraParser_PLSQLParser.SQL92_RESERVED_TRUE, "true");
        node.getParent().setChild(node.getChildIndex(), trueNode);
      }
    }
  }

  private boolean IsColumnRef(Tree node) {
    if (node.getType() == PantheraParser_PLSQLParser.CASCATED_ELEMENT &&
        node.getChild(0).getType() == PantheraParser_PLSQLParser.ANY_ELEMENT) {
      return true;
    } else {
      return false;
    }
  }

  private String getTableName(QueryInfo qf, CommonTree anyElement) throws SqlXlateException {
    String table = null;

    CommonTree currentSelect = (CommonTree) anyElement
        .getAncestor(PantheraParser_PLSQLParser.SQL92_RESERVED_SELECT);

    if (anyElement.getChildCount() > 1) {
      table = anyElement.getChild(0).getText();
      if (anyElement.getChildCount() > 2) {
        // schema.table
        table += ("." + anyElement.getChild(1).getText());
        // merge schema and table as HIVE does not support schema.table.column in where clause.
        anyElement.deleteChild(1);
        ((CommonTree)anyElement.getChild(0)).getToken().setText(table);
      }
      //
      // Return null table name if it is not a src table.
      //
      if (!qf.getSrcTblAliasForSelectKey(currentSelect).contains(table)) {
        table = null;
      }
    } else {
      String columnName = anyElement.getChild(0).getText();
      List<Column> fromRowInfo = qf.getRowInfo((CommonTree) currentSelect.getFirstChildWithType(
                                               PantheraParser_PLSQLParser.SQL92_RESERVED_FROM));
      for (Column col : fromRowInfo) {
        if (col.getColAlias().equals(columnName)) {
          table = col.getTblAlias();
          // Add table leaf node because HIVE needs table name for join operation.
          SqlASTNode tableNameNode = SqlXlateUtil.newSqlASTNode(PantheraParser_PLSQLParser.ID,
              table);
          CommonTree columnNode = (CommonTree) anyElement.getChild(0);
          anyElement.setChild(0, tableNameNode);
          anyElement.addChild(columnNode);
          break;
        }
      }
    }
    return table;
  }

  private void transformFromClause(QueryInfo qf, CommonTree oldFrom, JoinInfo joinInfo) throws SqlXlateException {
    Set<String> alreadyJoinedTables = new HashSet<String>();

    CommonTree topTableRef = (CommonTree) oldFrom.getChild(0);
    SqlXlateUtil.getSrcTblAlias((CommonTree) topTableRef.getChild(0), alreadyJoinedTables);
    assert (alreadyJoinedTables.size() == 1);
    String firstTable = (String) alreadyJoinedTables.toArray()[0];
    for (int i = 1; i < topTableRef.getChildCount(); i++) {
      CommonTree joinNode = (CommonTree) topTableRef.getChild(i);
      Set<String> srcTables = new HashSet<String>();
      SqlXlateUtil.getSrcTblAlias((CommonTree) joinNode
          .getFirstChildWithType(PantheraParser_PLSQLParser.TABLE_REF_ELEMENT), srcTables);
      assert (srcTables.size() == 1);
      String srcTable = (String) srcTables.toArray()[0];

      // if any column is referenced in join conditions, add missing table name for HIVE.
      CommonTree OnNode = (CommonTree) joinNode
          .getFirstChildWithType(PantheraParser_PLSQLParser.SQL92_RESERVED_ON);
      if (OnNode != null) {
        List<CommonTree> anyElementList = new ArrayList<CommonTree>();
        FilterBlockUtil.findNode(OnNode, PantheraParser_PLSQLParser.ANY_ELEMENT, anyElementList);
        for (CommonTree anyElement : anyElementList) {
          getTableName(qf, anyElement);
        }
      }

      for (String alreadyJoinedTable : alreadyJoinedTables) {
        JoinPair tableJoinPair = new JoinPair(alreadyJoinedTable, srcTable);
        List<CommonTree> JoinEqualityNodes = joinInfo.joinPairInfo.get(tableJoinPair);
        if (JoinEqualityNodes != null) {
          generateJoin(joinNode, JoinEqualityNodes);
          joinInfo.joinPairInfo.remove(tableJoinPair);
        }
      }
      alreadyJoinedTables.add(srcTable);

      List<CommonTree> joinFilters;
      if (i == 1) {
        //need consider the join filter of the first table
        joinFilters = joinInfo.joinFilterInfo.get(firstTable);
        if (joinFilters != null) {
          generateJoin(joinNode, joinFilters);
          joinInfo.joinFilterInfo.remove(firstTable);
        }
      }

      joinFilters = joinInfo.joinFilterInfo.get(srcTable);
      if (joinFilters != null) {
        generateJoin(joinNode, joinFilters);
        joinInfo.joinFilterInfo.remove(srcTable);
      }
    }

    if (!joinInfo.joinPairInfo.isEmpty() || !joinInfo.joinFilterInfo.isEmpty()) {
      throw new SqlXlateException("Cross join transformer: bad cross join!");
    }
  }

  private void generateJoin(CommonTree joinNode, List<CommonTree> joinConditionNodes) {
    CommonTree OnNode;
    CommonTree logicExprNode;

    if (joinConditionNodes == null) {
      return;
    }

    OnNode = (CommonTree) joinNode
        .getFirstChildWithType(PantheraParser_PLSQLParser.SQL92_RESERVED_ON);
    if (OnNode == null) {
      //
      // Generate the join condition sub-tree.
      //
      SqlASTNode newOnNode = SqlXlateUtil.newSqlASTNode(
          PantheraParser_PLSQLParser.SQL92_RESERVED_ON, "on");
      logicExprNode = SqlXlateUtil.newSqlASTNode(PantheraParser_PLSQLParser.LOGIC_EXPR,
          "LOGIC_EXPR");
      newOnNode.addChild(logicExprNode);

      if (joinNode.getChild(0).getType() == PantheraParser_PLSQLParser.CROSS_VK) {
        if (joinNode.getChild(0).getText().equals(PantheraExpParser.LEFTSEMI_STR)) {
          ((CommonTree) joinNode.getChild(0)).getToken().setType(PantheraExpParser.LEFTSEMI_VK);
          joinNode.addChild(newOnNode);
        } else if (joinNode.getChild(0).getText().equals(PantheraExpParser.LEFT_STR)) {
          ((CommonTree) joinNode.getChild(0)).getToken().setType(PantheraExpParser.LEFT_VK);
          joinNode.addChild(newOnNode);
        } else {
          // Remove the CROSS node
          joinNode.setChild(0, joinNode.getChild(1));
          joinNode.setChild(1, newOnNode);
        }
      } else {
        joinNode.addChild(newOnNode);
      }
    } else {
      logicExprNode = (CommonTree) OnNode.getChild(0);
    }

    addJoinCondition(joinConditionNodes, logicExprNode);
  }

  private void addJoinCondition(List<CommonTree> joinConditionNodes, CommonTree logicExpr) {
    Iterator<CommonTree> iterator = joinConditionNodes.iterator();
    CommonTree expressionRoot;
    if (logicExpr.getChildCount() == 0) {
      expressionRoot = iterator.next();
    } else {
      expressionRoot = (CommonTree) logicExpr.getChild(0);
    }

    while (iterator.hasNext()) {
      CommonTree andNode = SqlXlateUtil.newSqlASTNode(
          PantheraParser_PLSQLParser.SQL92_RESERVED_AND, "and");
      andNode.addChild(expressionRoot);
      andNode.addChild(iterator.next());
      expressionRoot = andNode;
    }

    if (logicExpr.getChildCount() == 0) {
      logicExpr.addChild(expressionRoot);
    } else {
      logicExpr.setChild(0, expressionRoot);
    }
  }
}
