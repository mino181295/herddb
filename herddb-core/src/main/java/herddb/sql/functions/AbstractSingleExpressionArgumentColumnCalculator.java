/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package herddb.sql.functions;

import herddb.model.StatementEvaluationContext;
import herddb.model.StatementExecutionException;
import herddb.model.Tuple;
import herddb.sql.AggregatedColumnCalculator;
import herddb.sql.SQLRecordPredicate;
import herddb.sql.expressions.CompiledSQLExpression;
import herddb.sql.expressions.SQLExpressionCompiler;
import herddb.utils.DataAccessor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.schema.Column;

/**
 *
 * @author enrico.olivelli
 */
public abstract class AbstractSingleExpressionArgumentColumnCalculator implements AggregatedColumnCalculator {

    final protected String fieldName;
    final protected CompiledSQLExpression expression;
    final protected ValueComputer valueExtractor;

    @FunctionalInterface
    public static interface ValueComputer {

        public Comparable apply(DataAccessor tuple) throws StatementExecutionException;
    }

    protected AbstractSingleExpressionArgumentColumnCalculator(String fieldName, Expression expression,
        StatementEvaluationContext context
    ) throws StatementExecutionException {
        this.fieldName = fieldName;
        this.expression = SQLExpressionCompiler.compileExpression(null, expression);
        valueExtractor = (DataAccessor t) -> {
            return (Comparable) this.expression.evaluate(t, context);
        };
    }
}
