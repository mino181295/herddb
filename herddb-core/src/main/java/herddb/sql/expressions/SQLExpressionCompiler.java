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
package herddb.sql.expressions;

import static herddb.sql.functions.BuiltinFunctions.CURRENT_TIMESTAMP;

import herddb.model.StatementExecutionException;
import herddb.sql.expressions.CompiledSQLExpression.BinaryExpressionBuilder;
import herddb.sql.functions.BuiltinFunctions;
import herddb.utils.RawString;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeKeyExpression;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;

/**
 * Created a pure Java implementation of the expression which represents the given jSQLParser Expression
 *
 * @author enrico.olivelli
 */
public class SQLExpressionCompiler {

    private static CompiledSQLExpression tryCompileBinaryExpression(
        String validatedTableAlias,
        BinaryExpression binExp,
        BinaryExpressionBuilder compiledExpClass) {

        CompiledSQLExpression left = compileExpression(validatedTableAlias, binExp.getLeftExpression());
        if (left == null) {
            return null;
        }
        CompiledSQLExpression right = compileExpression(validatedTableAlias, binExp.getRightExpression());
        if (right == null) {
            return null;
        }
        return compiledExpClass.build(binExp.isNot(), left, right);
    }

    // this method never returns NULL
    public static CompiledSQLExpression compileExpression(String validatedTableAlias,
        Expression exp) {
        if (exp instanceof BinaryExpression) {
            CompiledSQLExpression compiled = compileSpecialBinaryExpression(validatedTableAlias, exp);
            if (compiled != null) {
                return compiled;
            }
        }
        if (exp instanceof net.sf.jsqlparser.schema.Column) {
            return compileColumnExpression(validatedTableAlias, exp);
        } else if (exp instanceof StringValue) {
            return new ConstantExpression(RawString.of(((StringValue) exp).getValue()));
        } else if (exp instanceof LongValue) {
            try {
                return new ConstantExpression(((LongValue) exp).getValue());
            } catch (NumberFormatException largeNumber) {
                return new ConstantExpression(Double.valueOf(((LongValue) exp).getStringValue()));
            }
        } else if (exp instanceof DoubleValue) {
            return new ConstantExpression(((DoubleValue) exp).getValue());
        } else if (exp instanceof TimestampValue) {
            return new ConstantExpression(((TimestampValue) exp).getValue());
        } else if (exp instanceof NullValue) {
            return new ConstantExpression(null);
        } else if (exp instanceof TimeKeyExpression) {
            TimeKeyExpression ext = (TimeKeyExpression) exp;
            if (CURRENT_TIMESTAMP.equalsIgnoreCase(ext.getStringValue())) {
                return new ConstantExpression(new java.sql.Timestamp(System.currentTimeMillis()));
            } else {
                throw new StatementExecutionException("unhandled expression " + exp);
            }
        } else if (exp instanceof JdbcParameter) {
            int index = ((JdbcParameter) exp).getIndex() - 1;
            return new JdbcParameterExpression(index);
        } else if (exp instanceof AndExpression) {
            return tryCompileBinaryExpression(validatedTableAlias, (BinaryExpression) exp, (a, b, c) -> new CompiledAndExpression(a, b, c));
        } else if (exp instanceof OrExpression) {
            return tryCompileBinaryExpression(validatedTableAlias, (BinaryExpression) exp, (a, b, c) -> new CompiledOrExpression(a, b, c));
        } else if (exp instanceof Function) {
            return CompiledFunction.create((Function) exp, validatedTableAlias);
        } else if (exp instanceof Addition) {
            return tryCompileBinaryExpression(validatedTableAlias, (BinaryExpression) exp, (a, b, c) -> new CompiledAddExpression(a, b, c));
        } else if (exp instanceof Subtraction) {
            return tryCompileBinaryExpression(validatedTableAlias, (BinaryExpression) exp, (a, b, c) -> new CompiledSubtractExpression(a, b, c));
        } else if (exp instanceof Multiplication) {
            return tryCompileBinaryExpression(validatedTableAlias, (BinaryExpression) exp, (a, b, c) -> new CompiledMultiplyExpression(a, b, c));
        } else if (exp instanceof Division) {
            return tryCompileBinaryExpression(validatedTableAlias, (BinaryExpression) exp, (a, b, c) -> new CompiledDivideExpression(a, b, c));
        } else if (exp instanceof Parenthesis) {
            Parenthesis p = (Parenthesis) exp;
            CompiledSQLExpression inner = compileExpression(validatedTableAlias, p.getExpression());
            return new CompiledParenthesisExpression(p.isNot(), inner);
        } else if (exp instanceof EqualsTo) {
            return tryCompileBinaryExpression(validatedTableAlias, (BinaryExpression) exp, (a, b, c) -> new CompiledEqualsExpression(a, b, c));
        } else if (exp instanceof NotEqualsTo) {
            return tryCompileBinaryExpression(validatedTableAlias, (BinaryExpression) exp, (a, b, c) -> new CompiledNotEqualsExpression(a, b, c));
        } else if (exp instanceof MinorThan) {
            return tryCompileBinaryExpression(validatedTableAlias, (BinaryExpression) exp, (a, b, c) -> new CompiledMinorThenExpression(a, b, c));
        } else if (exp instanceof MinorThanEquals) {
            return tryCompileBinaryExpression(validatedTableAlias, (BinaryExpression) exp, (a, b, c) -> new CompiledMinorThenEqualsExpression(a, b, c));
        } else if (exp instanceof GreaterThan) {
            return tryCompileBinaryExpression(validatedTableAlias, (BinaryExpression) exp, (a, b, c) -> new CompiledGreaterThenExpression(a, b, c));
        } else if (exp instanceof GreaterThanEquals) {
            return tryCompileBinaryExpression(validatedTableAlias, (BinaryExpression) exp, (a, b, c) -> new CompiledGreaterThenEqualsExpression(a, b, c));
        } else if (exp instanceof LikeExpression) {
            return tryCompileBinaryExpression(validatedTableAlias, (BinaryExpression) exp, (a, b, c) -> new CompiledLikeExpression(a, b, c));
        } else if (exp instanceof Between) {
            return CompiledBetweenExpression.create(validatedTableAlias, (Between) exp);
        } else if (exp instanceof SignedExpression) {
            SignedExpression s = (SignedExpression) exp;
            CompiledSQLExpression inner = compileExpression(validatedTableAlias, s.getExpression());
            return new CompiledSignedExpression(s.getSign(), inner);
        } else if (exp instanceof InExpression) {
            InExpression in = (InExpression) exp;
            return CompiledInExpression.create(in, validatedTableAlias);
        } else if (exp instanceof IsNullExpression) {
            IsNullExpression i = (IsNullExpression) exp;
            CompiledSQLExpression left = compileExpression(validatedTableAlias, i.getLeftExpression());
            return new CompiledIsNullExpression(i.isNot(), left);
        } else if (exp instanceof CaseExpression) {
            return CompiledCaseExpression.create(validatedTableAlias, (CaseExpression) exp);
        }
        throw new StatementExecutionException("unsupported operand " + exp.getClass() + ", expression is " + exp);
    }

    private static CompiledSQLExpression compileColumnExpression(String validatedTableAlias, Expression exp) {
        net.sf.jsqlparser.schema.Column c = (net.sf.jsqlparser.schema.Column) exp;
        if (validatedTableAlias != null) {
            if (c.getTable() != null && c.getTable().getName() != null
                && !c.getTable().getName().equals(validatedTableAlias)) {
                throw new StatementExecutionException("invalid column name " + c.getColumnName()
                    + " invalid table name " + c.getTable().getName() + ", expecting " + validatedTableAlias);
            }
        }

        String columnName = c.getColumnName();
        if (BuiltinFunctions.BOOLEAN_TRUE.equalsIgnoreCase(columnName)) {
            return new ConstantExpression(Boolean.TRUE);
        } else if (BuiltinFunctions.BOOLEAN_FALSE.equalsIgnoreCase(columnName)) {
            return new ConstantExpression(Boolean.FALSE);
        } else {
            return new ColumnExpression(columnName);
        }
    }

    private static CompiledSQLExpression compileSpecialBinaryExpression(String validatedTableAlias, Expression exp) {
        BinaryExpression be = (BinaryExpression) exp;

        // MOST frequent expressions "TABLE.COLUMNNAME OPERATOR ?", we can hardcode the access to the column and to the JDBC parameter
        if (be.getLeftExpression() instanceof net.sf.jsqlparser.schema.Column) {
            net.sf.jsqlparser.schema.Column c = (net.sf.jsqlparser.schema.Column) be.getLeftExpression();
            if (validatedTableAlias != null) {
                if (c.getTable() != null && c.getTable().getName() != null
                    && !c.getTable().getName().equals(validatedTableAlias)) {
                    return null;
                }
            }

            String columnName = c.getColumnName();
            switch (columnName) {
                case BuiltinFunctions.BOOLEAN_TRUE:
                    return null;
                case BuiltinFunctions.BOOLEAN_FALSE:
                    return null;
                default:
                    // OK !
                    break;
            }

            if (be.getRightExpression() instanceof JdbcParameter) {
                JdbcParameter jdbcParam = (JdbcParameter) be.getRightExpression();
                int jdbcIndex = jdbcParam.getIndex() - 1;
                if (be instanceof EqualsTo) {
                    return new ColumnEqualsJdbcParameter(be.isNot(), columnName, jdbcIndex);
                } else if (be instanceof NotEqualsTo) {
                    return new ColumnNotEqualsJdbcParameter(be.isNot(), columnName, jdbcIndex);
                } else if (be instanceof GreaterThanEquals) {
                    return new ColumnGreaterThanEqualsJdbcParameter(be.isNot(), columnName, jdbcIndex);
                } else if (be instanceof GreaterThan) {
                    return new ColumnGreaterThanJdbcParameter(be.isNot(), columnName, jdbcIndex);
                } else if (be instanceof MinorThan) {
                    return new ColumnMinorThanJdbcParameter(be.isNot(), columnName, jdbcIndex);
                } else if (be instanceof MinorThanEquals) {
                    return new ColumnMinorThanEqualsJdbcParameter(be.isNot(), columnName, jdbcIndex);
                }
            } // TODO handle "TABLE.COLUMNNAME OPERATOR CONSTANT"
        }
        return null;
    }
}
