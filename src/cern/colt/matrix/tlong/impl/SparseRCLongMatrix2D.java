/*
Copyright (C) 1999 CERN - European Organization for Nuclear Research.
Permission to use, copy, modify, distribute and sell this software and its documentation for any purpose 
is hereby granted without fee, provided that the above copyright notice appear in all copies and 
that both that copyright notice and this permission notice appear in supporting documentation. 
CERN makes no representations about the suitability of this software for any purpose. 
It is provided "as is" without expressed or implied warranty.
 */
package cern.colt.matrix.tlong.impl;

import java.util.concurrent.Future;

import cern.colt.list.tint.IntArrayList;
import cern.colt.list.tlong.LongArrayList;
import cern.colt.matrix.tlong.LongMatrix1D;
import cern.colt.matrix.tlong.LongMatrix2D;
import edu.emory.mathcs.utils.ConcurrencyUtils;

/**
 * Sparse row-compressed 2-d matrix holding <tt>int</tt> elements. First see the
 * <a href="package-summary.html">package summary</a> and javadoc <a
 * href="package-tree.html">tree view</a> to get the broad picture.
 * <p>
 * <b>Implementation:</b>
 * <p>
 * Longernally uses the standard sparse row-compressed format, with two important
 * differences that broaden the applicability of this storage format:
 * <ul>
 * <li>We use a {@link cern.colt.list.tlong.LongArrayList} and
 * {@link cern.colt.list.tlong.LongArrayList} to hold the column indexes and
 * nonzero values, respectively. This improves set(...) performance, because the
 * standard way of using non-resizable primitive arrays causes excessive memory
 * allocation, garbage collection and array copying. The small downside of this
 * is that set(...,0) does not free memory (The capacity of an arraylist does
 * not shrink upon element removal).
 * <li>Column indexes are kept sorted within a row. This both improves get and
 * set performance on rows with many non-zeros, because we can use a binary
 * search. (Experiments show that this hurts < 10% on rows with < 4 nonZeros.)
 * </ul>
 * <br>
 * Note that this implementation is not synchronized.
 * <p>
 * <b>Memory requirements:</b>
 * <p>
 * Cells that
 * <ul>
 * <li>are never set to non-zero values do not use any memory.
 * <li>switch from zero to non-zero state do use memory.
 * <li>switch back from non-zero to zero state also do use memory. Their memory
 * is <i>not</i> automatically reclaimed (because of the lists vs. arrays).
 * Reclamation can be triggered via {@link #trimToSize()}.
 * </ul>
 * <p>
 * <tt>memory [bytes] = 4*rows + 12 * nonZeros</tt>. <br>
 * Where <tt>nonZeros = cardinality()</tt> is the number of non-zero cells.
 * Thus, a 1000 x 1000 matrix with 1000000 non-zero cells consumes 11.5 MB. The
 * same 1000 x 1000 matrix with 1000 non-zero cells consumes 15 KB.
 * <p>
 * <b>Time complexity:</b>
 * <p>
 * Getting a cell value takes time<tt> O(log nzr)</tt> where <tt>nzr</tt> is the
 * number of non-zeros of the touched row. This is usually quick, because
 * typically there are only few nonzeros per row. So, in practice, get has
 * <i>expected</i> constant time. Setting a cell value takes <i> </i>worst-case
 * time <tt>O(nz)</tt> where <tt>nzr</tt> is the total number of non-zeros in
 * the matrix. This can be extremely slow, but if you traverse coordinates
 * properly (i.e. upwards), each write is done much quicker:
 * <table>
 * <td class="PRE">
 * 
 * <pre>
 * // rather quick
 * matrix.assign(0);
 * for (int row = 0; row &lt; rows; row++) {
 *     for (int column = 0; column &lt; columns; column++) {
 *         if (someCondition)
 *             matrix.setQuick(row, column, someValue);
 *     }
 * }
 * 
 * // poor
 * matrix.assign(0);
 * for (int row = rows; --row &gt;= 0;) {
 *     for (int column = columns; --column &gt;= 0;) {
 *         if (someCondition)
 *             matrix.setQuick(row, column, someValue);
 *     }
 * }
 * </pre>
 * 
 * </td>
 * </table>
 * If for whatever reasons you can't iterate properly, consider to create an
 * empty dense matrix, store your non-zeros in it, then call
 * <tt>sparse.assign(dense)</tt>. Under the circumstances, this is still rather
 * quick.
 * <p>
 * Fast iteration over non-zeros can be done via {@link #forEachNonZero}, which
 * supplies your function with row, column and value of each nonzero. Although
 * the internally implemented version is a bit more sophisticated, here is how a
 * quite efficient user-level matrix-vector multiplication could look like:
 * <table>
 * <td class="PRE">
 * 
 * <pre>
 * // Linear algebraic y = A * x
 * A.forEachNonZero(new cern.colt.function.LongLongLongFunction() {
 *     public int apply(int row, int column, int value) {
 *         y.setQuick(row, y.getQuick(row) + value * x.getQuick(column));
 *         return value;
 *     }
 * });
 * </pre>
 * 
 * </td>
 * </table>
 * <p>
 * Here is how a a quite efficient user-level combined scaling operation could
 * look like:
 * <table>
 * <td class="PRE">
 * 
 * <pre>
 * // Elementwise A = A + alpha*B
 * B.forEachNonZero(new cern.colt.function.LongLongLongFunction() {
 *     public int apply(int row, int column, int value) {
 *         A.setQuick(row, column, A.getQuick(row, column) + alpha * value);
 *         return value;
 *     }
 * });
 * </pre>
 * 
 * </td>
 * </table>
 * Method {@link #assign(LongMatrix2D,cern.colt.function.tlong.LongLongFunction)}
 * does just that if you supply
 * {@link cern.jet.math.tlong.LongFunctions#plusMultSecond} as argument.
 * 
 * 
 * @author wolfgang.hoschek@cern.ch
 * @version 0.9, 04/14/2000
 */
public class SparseRCLongMatrix2D extends WrapperLongMatrix2D {
    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    /*
     * The elements of the matrix.
     */
    protected IntArrayList columnIndexes;

    protected LongArrayList values;

    protected int[] rowPointers;

    /**
     * Constructs a matrix with a copy of the given values. <tt>values</tt> is
     * required to have the form <tt>values[row][column]</tt> and have exactly
     * the same number of columns in every row.
     * <p>
     * The values are copied. So subsequent changes in <tt>values</tt> are not
     * reflected in the matrix, and vice-versa.
     * 
     * @param values
     *            The values to be filled into the new matrix.
     * @throws IllegalArgumentException
     *             if
     *             <tt>for any 1 &lt;= row &lt; values.length: values[row].length != values[row-1].length</tt>
     *             .
     */
    public SparseRCLongMatrix2D(long[][] values) {
        this(values.length, values.length == 0 ? 0 : values[0].length);
        assign(values);
    }

    public SparseRCLongMatrix2D(int rows, int columns, int[] rowPointers, IntArrayList columnIndexes, LongArrayList values) {
        super(null);
        try {
            setUp(rows, columns);
        } catch (IllegalArgumentException exc) { // we can hold rows*columns>Long.MAX_VALUE cells !
            if (!"matrix too large".equals(exc.getMessage()))
                throw exc;
        }
        this.rowPointers = rowPointers;
        this.columnIndexes = columnIndexes;
        this.values = values;
    }

    /**
     * Constructs a matrix with a given number of rows and columns. All entries
     * are initially <tt>0</tt>.
     * 
     * @param rows
     *            the number of rows the matrix shall have.
     * @param columns
     *            the number of columns the matrix shall have.
     * @throws IllegalArgumentException
     *             if
     *             <tt>rows<0 || columns<0 || (int)columns*rows > Long.MAX_VALUE</tt>
     *             .
     */
    public SparseRCLongMatrix2D(int rows, int columns) {
        super(null);
        try {
            setUp(rows, columns);
        } catch (IllegalArgumentException exc) { // we can hold rows*columns>Long.MAX_VALUE cells !
            if (!"matrix too large".equals(exc.getMessage()))
                throw exc;
        }
        columnIndexes = new IntArrayList();
        values = new LongArrayList();
        rowPointers = new int[rows + 1];
    }

    /**
     * Constructs a matrix with a given number of rows and columns. All entries
     * are initially <tt>0</tt>.
     * 
     * @param rows
     *            the number of rows the matrix shall have.
     * @param columns
     *            the number of columns the matrix shall have.
     * @throws IllegalArgumentException
     *             if
     *             <tt>rows<0 || columns<0 || (int)columns*rows > Long.MAX_VALUE</tt>
     *             .
     */
    public SparseRCLongMatrix2D(int rows, int columns, int nzmax) {
        super(null);
        try {
            setUp(rows, columns);
        } catch (IllegalArgumentException exc) { // we can hold rows*columns>Long.MAX_VALUE cells !
            if (!"matrix too large".equals(exc.getMessage()))
                throw exc;
        }
        columnIndexes = new IntArrayList(nzmax);
        values = new LongArrayList(nzmax);
        rowPointers = new int[rows + 1];
    }

    /**
     * Sets all nonzero cells to the state specified by <tt>value</tt>.
     * 
     * @param value
     *            the value to be filled into the cells.
     * @return <tt>this</tt> (for convenience only).
     */
    @Override
    public LongMatrix2D assign(long value) {
        // overriden for performance only
        if (value == 0) {
            columnIndexes.clear();
            values.clear();
            rowPointers = new int[rows + 1];
            //            for (int i = starts.length; --i >= 0;)
            //                starts[i] = 0;
        } else {
            //            super.assign(value);
            int nnz = cardinality();
            for (int i = 0; i < nnz; i++) {
                values.setQuick(i, value);
            }
        }
        return this;
    }

    /**
     * Assigns the result of a function to each nonzero cell;
     * 
     * @param function
     *            a function object taking as argument the current cell's value.
     * @return <tt>this</tt> (for convenience only).
     * @see cern.jet.math.tlong.LongFunctions
     */
    @Override
    public LongMatrix2D assign(final cern.colt.function.tlong.LongFunction function) {
        if (function instanceof cern.jet.math.tlong.LongMult) { // x[i] = mult*x[i]
            final long alpha = ((cern.jet.math.tlong.LongMult) function).multiplicator;
            if (alpha == 1)
                return this;
            if (alpha == 0)
                return assign(0);
            if (alpha != alpha)
                return assign(alpha); // the funny definition of isNaN(). This should better not happen.

            final long[] valuesE = values.elements();
            int nthreads = ConcurrencyUtils.getNumberOfThreads();
            if ((nthreads > 1) && (cardinality() >= ConcurrencyUtils.getThreadsBeginN_2D())) {
                nthreads = Math.min(nthreads, valuesE.length);
                Future<?>[] futures = new Future[nthreads];
                int k = valuesE.length / nthreads;
                for (int j = 0; j < nthreads; j++) {
                    final int firstIdx = j * k;
                    final int lastIdx = (j == nthreads - 1) ? valuesE.length : firstIdx + k;
                    futures[j] = ConcurrencyUtils.submit(new Runnable() {
                        public void run() {
                            for (int i = firstIdx; i < lastIdx; i++) {
                                valuesE[i] *= alpha;
                            }
                        }
                    });
                }
                ConcurrencyUtils.waitForCompletion(futures);
            } else {
                for (int j = values.size(); --j >= 0;) {
                    valuesE[j] *= alpha;
                }
            }
        } else {
            forEachNonZero(new cern.colt.function.tlong.IntIntLongFunction() {
                public long apply(int i, int j, long value) {
                    return function.apply(value);
                }
            });
        }
        return this;
    }

    /**
     * Replaces all cell values of the receiver with the values of another
     * matrix. Both matrices must have the same number of rows and columns. If
     * both matrices share the same cells (as is the case if they are views
     * derived from the same matrix) and intersect in an ambiguous way, then
     * replaces <i>as if</i> using an intermediate auxiliary deep copy of
     * <tt>other</tt>.
     * 
     * @param source
     *            the source matrix to copy from (may be identical to the
     *            receiver).
     * @return <tt>this</tt> (for convenience only).
     * @throws IllegalArgumentException
     *             if
     *             <tt>columns() != source.columns() || rows() != source.rows()</tt>
     */
    @Override
    public LongMatrix2D assign(LongMatrix2D source) {
        if (source == this)
            return this; // nothing to do
        checkShape(source);
        // overriden for performance only
        if (!(source instanceof SparseRCLongMatrix2D)) {
            assign(0);
            source.forEachNonZero(new cern.colt.function.tlong.IntIntLongFunction() {
                public long apply(int i, int j, long value) {
                    setQuick(i, j, value);
                    return value;
                }
            });
            /*
             * indexes.clear(); values.clear(); int nonZeros=0; for (int row=0;
             * row<rows; row++) { starts[row]=nonZeros; for (int column=0;
             * column<columns; column++) { int v =
             * source.getQuick(row,column); if (v!=0) { values.add(v);
             * indexes.add(column); nonZeros++; } } } starts[rows]=nonZeros;
             */
            return this;
        }

        // even quicker
        SparseRCLongMatrix2D other = (SparseRCLongMatrix2D) source;

        System.arraycopy(other.rowPointers, 0, this.rowPointers, 0, this.rowPointers.length);
        int s = other.columnIndexes.size();
        this.columnIndexes.setSize(s);
        this.values.setSize(s);
        this.columnIndexes.replaceFromToWithFrom(0, s - 1, other.columnIndexes, 0);
        this.values.replaceFromToWithFrom(0, s - 1, other.values, 0);

        return this;
    }

    @Override
    public LongMatrix2D assign(final LongMatrix2D y, cern.colt.function.tlong.LongLongFunction function) {
        checkShape(y);

        if (function instanceof cern.jet.math.tlong.LongPlusMultSecond) { // x[i] = x[i] + alpha*y[i]
            final long alpha = ((cern.jet.math.tlong.LongPlusMultSecond) function).multiplicator;
            if (alpha == 0)
                return this; // nothing to do
            y.forEachNonZero(new cern.colt.function.tlong.IntIntLongFunction() {
                public long apply(int i, int j, long value) {
                    setQuick(i, j, getQuick(i, j) + alpha * value);
                    return value;
                }
            });
            return this;
        }

        if (function instanceof cern.jet.math.tlong.LongPlusMultFirst) { // x[i] = alpha*x[i] + y[i]
            final long alpha = ((cern.jet.math.tlong.LongPlusMultFirst) function).multiplicator;
            if (alpha == 0)
                return assign(y);
            y.forEachNonZero(new cern.colt.function.tlong.IntIntLongFunction() {
                public long apply(int i, int j, long value) {
                    setQuick(i, j, alpha * getQuick(i, j) + value);
                    return value;
                }
            });
            return this;
        }

        int nthreads = ConcurrencyUtils.getNumberOfThreads();

        if (function == cern.jet.math.tlong.LongFunctions.mult) { // x[i] = x[i] * y[i]
            final int[] indexesE = columnIndexes.elements();
            final long[] valuesE = values.elements();

            if ((nthreads > 1) && (cardinality() >= ConcurrencyUtils.getThreadsBeginN_2D())) {
                nthreads = Math.min(nthreads, rows);
                Future<?>[] futures = new Future[nthreads];
                int k = rows / nthreads;
                for (int j = 0; j < nthreads; j++) {
                    final int firstRow = j * k;
                    final int lastRow = (j == nthreads - 1) ? rows : firstRow + k;
                    futures[j] = ConcurrencyUtils.submit(new Runnable() {
                        public void run() {
                            for (int i = firstRow; i < lastRow; i++) {
                                int high = rowPointers[i + 1];
                                for (int k = rowPointers[i]; k < high; k++) {
                                    int j = indexesE[k];
                                    valuesE[k] *= y.getQuick(i, j);
                                    if (valuesE[k] == 0)
                                        remove(i, j);
                                }
                            }
                        }
                    });
                }
                ConcurrencyUtils.waitForCompletion(futures);
            } else {
                for (int i = rowPointers.length - 1; --i >= 0;) {
                    int low = rowPointers[i];
                    for (int k = rowPointers[i + 1]; --k >= low;) {
                        int j = indexesE[k];
                        valuesE[k] *= y.getQuick(i, j);
                        if (valuesE[k] == 0)
                            remove(i, j);
                    }
                }
            }
            return this;
        }

        if (function == cern.jet.math.tlong.LongFunctions.div) { // x[i] = x[i] / y[i]
            final int[] indexesE = columnIndexes.elements();
            final long[] valuesE = values.elements();

            if ((nthreads > 1) && (cardinality() >= ConcurrencyUtils.getThreadsBeginN_2D())) {
                nthreads = Math.min(nthreads, rows);
                Future<?>[] futures = new Future[nthreads];
                int k = rows / nthreads;
                for (int j = 0; j < nthreads; j++) {
                    final int firstRow = j * k;
                    final int lastRow = (j == nthreads - 1) ? rows : firstRow + k;

                    futures[j] = ConcurrencyUtils.submit(new Runnable() {
                        public void run() {
                            for (int i = firstRow; i < lastRow; i++) {
                                int high = rowPointers[i + 1];
                                for (int k = rowPointers[i]; k < high; k++) {
                                    int j = indexesE[k];
                                    valuesE[k] /= y.getQuick(i, j);
                                    if (valuesE[k] == 0)
                                        remove(i, j);
                                }
                            }
                        }
                    });
                }
                ConcurrencyUtils.waitForCompletion(futures);
            } else {
                for (int i = rowPointers.length - 1; --i >= 0;) {
                    int low = rowPointers[i];
                    for (int k = rowPointers[i + 1]; --k >= low;) {
                        int j = indexesE[k];
                        valuesE[k] /= y.getQuick(i, j);
                        if (valuesE[k] == 0)
                            remove(i, j);
                    }
                }
            }
            return this;
        }

        return super.assign(y, function);
    }

    @Override
    public LongMatrix2D forEachNonZero(final cern.colt.function.tlong.IntIntLongFunction function) {
        final int[] indexesE = columnIndexes.elements();
        final long[] valuesE = values.elements();
        int nthreads = ConcurrencyUtils.getNumberOfThreads();
        if ((nthreads > 1) && (cardinality() >= ConcurrencyUtils.getThreadsBeginN_2D())) {
            nthreads = Math.min(nthreads, rows);
            Future<?>[] futures = new Future[nthreads];
            int k = rows / nthreads;
            for (int j = 0; j < nthreads; j++) {
                final int firstRow = j * k;
                final int lastRow = (j == nthreads - 1) ? rows : firstRow + k;
                futures[j] = ConcurrencyUtils.submit(new Runnable() {
                    public void run() {
                        for (int i = firstRow; i < lastRow; i++) {
                            int high = rowPointers[i + 1];
                            for (int k = rowPointers[i]; k < high; k++) {
                                int j = indexesE[k];
                                long value = valuesE[k];
                                long r = function.apply(i, j, value);
                                if (r != value)
                                    valuesE[k] = r;
                            }
                        }
                    }
                });
            }
            ConcurrencyUtils.waitForCompletion(futures);
        } else {
            for (int i = rowPointers.length - 1; --i >= 0;) {
                int low = rowPointers[i];
                for (int k = rowPointers[i + 1]; --k >= low;) {
                    int j = indexesE[k];
                    long value = valuesE[k];
                    long r = function.apply(i, j, value);
                    if (r != value)
                        valuesE[k] = r;
                }
            }
        }
        return this;
    }

    /**
     * Returns the content of this matrix if it is a wrapper; or <tt>this</tt>
     * otherwise. Override this method in wrappers.
     */
    @Override
    protected LongMatrix2D getContent() {
        return this;
    }

    public IntArrayList getColumnindexes() {
        return columnIndexes;
    }

    public int[] getRowPointers() {
        return rowPointers;
    }

    public LongArrayList getValues() {
        return values;
    }

    /**
     * Returns the matrix cell value at coordinate <tt>[row,column]</tt>.
     * 
     * <p>
     * Provided with invalid parameters this method may return invalid objects
     * without throwing any exception. <b>You should only use this method when
     * you are absolutely sure that the coordinate is within bounds.</b>
     * Precondition (unchecked):
     * <tt>0 &lt;= column &lt; columns() && 0 &lt;= row &lt; rows()</tt>.
     * 
     * @param row
     *            the index of the row-coordinate.
     * @param column
     *            the index of the column-coordinate.
     * @return the value at the specified coordinate.
     */
    @Override
    public long getQuick(int row, int column) {
        int k = columnIndexes.binarySearchFromTo(column, rowPointers[row], rowPointers[row + 1] - 1);
        long v = 0;
        if (k >= 0)
            v = values.getQuick(k);
        return v;
    }

    protected synchronized void insert(int row, int column, int index, long value) {
        columnIndexes.beforeInsert(index, column);
        values.beforeInsert(index, value);
        for (int i = rowPointers.length; --i > row;)
            rowPointers[i]++;
    }

    /**
     * Construct and returns a new empty matrix <i>of the same dynamic type</i>
     * as the receiver, having the specified number of rows and columns. For
     * example, if the receiver is an instance of type <tt>DenseLongMatrix2D</tt>
     * the new matrix must also be of type <tt>DenseLongMatrix2D</tt>, if the
     * receiver is an instance of type <tt>SparseLongMatrix2D</tt> the new matrix
     * must also be of type <tt>SparseLongMatrix2D</tt>, etc. In general, the new
     * matrix should have internal parametrization as similar as possible.
     * 
     * @param rows
     *            the number of rows the matrix shall have.
     * @param columns
     *            the number of columns the matrix shall have.
     * @return a new empty matrix of the same dynamic type.
     */
    @Override
    public LongMatrix2D like(int rows, int columns) {
        return new SparseRCLongMatrix2D(rows, columns);
    }

    /**
     * Construct and returns a new 1-d matrix <i>of the corresponding dynamic
     * type</i>, entirely independent of the receiver. For example, if the
     * receiver is an instance of type <tt>DenseLongMatrix2D</tt> the new matrix
     * must be of type <tt>DenseLongMatrix1D</tt>, if the receiver is an instance
     * of type <tt>SparseLongMatrix2D</tt> the new matrix must be of type
     * <tt>SparseLongMatrix1D</tt>, etc.
     * 
     * @param size
     *            the number of cells the matrix shall have.
     * @return a new matrix of the corresponding dynamic type.
     */
    @Override
    public LongMatrix1D like1D(int size) {
        return new SparseLongMatrix1D(size);
    }

    protected void remove(int row, int index) {
        columnIndexes.remove(index);
        values.remove(index);
        for (int i = rowPointers.length; --i > row;)
            rowPointers[i]--;
    }

    @Override
    public int cardinality() {
        return columnIndexes.size();
    }

    /**
     * Sets the matrix cell at coordinate <tt>[row,column]</tt> to the specified
     * value.
     * 
     * <p>
     * Provided with invalid parameters this method may access illegal indexes
     * without throwing any exception. <b>You should only use this method when
     * you are absolutely sure that the coordinate is within bounds.</b>
     * Precondition (unchecked):
     * <tt>0 &lt;= column &lt; columns() && 0 &lt;= row &lt; rows()</tt>.
     * 
     * @param row
     *            the index of the row-coordinate.
     * @param column
     *            the index of the column-coordinate.
     * @param value
     *            the value to be filled into the specified cell.
     */
    @Override
    public synchronized void setQuick(int row, int column, long value) {
        int k = columnIndexes.binarySearchFromTo(column, rowPointers[row], rowPointers[row + 1] - 1);
        if (k >= 0) { // found
            if (value == 0)
                remove(row, k);
            else
                values.setQuick(k, value);
            return;
        }

        if (value != 0) {
            k = -k - 1;
            insert(row, column, k, value);
        }
    }

    public DenseLongMatrix2D getFull() {
        final DenseLongMatrix2D full = new DenseLongMatrix2D(rows, columns);
        forEachNonZero(new cern.colt.function.tlong.IntIntLongFunction() {
            public long apply(int i, int j, long value) {
                full.setQuick(i, j, getQuick(i, j));
                return value;
            }
        });
        return full;
    }

    @Override
    public void trimToSize() {
        columnIndexes.trimToSize();
        values.trimToSize();
    }

    @Override
    public LongMatrix1D zMult(LongMatrix1D y, LongMatrix1D z) {
        int m = rows;
        int n = columns;

        if (z == null)
            z = new DenseLongMatrix1D(m);

        if (!(y instanceof DenseLongMatrix1D && z instanceof DenseLongMatrix1D)) {
            return super.zMult(y, z);
        }

        if (n != y.size() || m > z.size())
            throw new IllegalArgumentException("Incompatible args: " + this.toStringShort() + ", " + y.toStringShort()
                    + ", " + z.toStringShort());

        DenseLongMatrix1D zz = (DenseLongMatrix1D) z;
        final long[] zElements = zz.elements;
        final int zStride = zz.stride();
        final int zi = (int) z.index(0);

        DenseLongMatrix1D yy = (DenseLongMatrix1D) y;
        final long[] yElements = yy.elements;
        final int yStride = yy.stride();
        final int yi = (int) y.index(0);

        final int[] columnIndexesElements = columnIndexes.elements();
        final long[] valuesElements = values.elements();

        int nthreads = ConcurrencyUtils.getNumberOfThreads();
        if ((nthreads > 1) && (cardinality() >= ConcurrencyUtils.getThreadsBeginN_2D())) {
            nthreads = Math.min(nthreads, rows);
            Future<?>[] futures = new Future[nthreads];
            int k = rows / nthreads;
            for (int j = 0; j < nthreads; j++) {
                final int firstRow = j * k;
                final int lastRow = (j == nthreads - 1) ? rows : firstRow + k;

                futures[j] = ConcurrencyUtils.submit(new Runnable() {
                    public void run() {
                        int zidx = zi + firstRow * zStride;
                        for (int i = firstRow; i < lastRow; i++) {
                            int high = rowPointers[i + 1];
                            int sum = 0;
                            for (int k = rowPointers[i]; k < high; k++) {
                                int j = columnIndexesElements[k];
                                sum += valuesElements[k] * yElements[yi + yStride * j];
                            }
                            zElements[zidx] = sum;
                            zidx += zStride;
                        }

                    }
                });
            }
            ConcurrencyUtils.waitForCompletion(futures);
        } else {
            int s = rowPointers.length - 1;
            int zidx = zi;
            for (int i = 0; i < s; i++) {
                int high = rowPointers[i + 1];
                int sum = 0;
                for (int k = rowPointers[i]; k < high; k++) {
                    int j = columnIndexesElements[k];
                    sum += valuesElements[k] * yElements[yi + yStride * j];
                }
                zElements[zidx] = sum;
                zidx += zStride;
            }
        }
        return z;
    }

    @Override
    public LongMatrix1D zMult(LongMatrix1D y, LongMatrix1D z, final int alpha, final int beta, final boolean transposeA) {
        int m = rows;
        int n = columns;
        if (transposeA) {
            m = columns;
            n = rows;
        }

        boolean ignore = (z == null || !transposeA);
        if (z == null)
            z = new DenseLongMatrix1D(m);

        if (!(y instanceof DenseLongMatrix1D && z instanceof DenseLongMatrix1D)) {
            return super.zMult(y, z, alpha, beta, transposeA);
        }

        if (n != y.size() || m > z.size())
            throw new IllegalArgumentException("Incompatible args: "
                    + ((transposeA ? viewDice() : this).toStringShort()) + ", " + y.toStringShort() + ", "
                    + z.toStringShort());

        DenseLongMatrix1D zz = (DenseLongMatrix1D) z;
        final long[] zElements = zz.elements;
        final int zStride = zz.stride();
        final int zi = (int) z.index(0);

        DenseLongMatrix1D yy = (DenseLongMatrix1D) y;
        final long[] yElements = yy.elements;
        final int yStride = yy.stride();
        final int yi = (int) y.index(0);

        if (transposeA) {
            if ((!ignore) && (beta != 1.0))
                z.assign(cern.jet.math.tlong.LongFunctions.mult(beta));
        }

        final int[] idx = columnIndexes.elements();
        final long[] vals = values.elements();
        int s = rowPointers.length - 1;
        int nthreads = ConcurrencyUtils.getNumberOfThreads();
        if ((nthreads > 1) && (cardinality() >= ConcurrencyUtils.getThreadsBeginN_2D())) {
            nthreads = Math.min(nthreads, rows);
            Future<?>[] futures = new Future[nthreads];
            int k = rows / nthreads;
            for (int j = 0; j < nthreads; j++) {
                final int firstRow = j * k;
                final int lastRow = (j == nthreads - 1) ? rows : firstRow + k;

                futures[j] = ConcurrencyUtils.submit(new Runnable() {
                    public void run() {
                        int zidx = zi + firstRow * zStride;
                        if (!transposeA) {
                            if (beta == 0.0) {
                                for (int i = firstRow; i < lastRow; i++) {
                                    int high = rowPointers[i + 1];
                                    int sum = 0;
                                    for (int k = rowPointers[i]; k < high; k++) {
                                        int j = idx[k];
                                        sum += vals[k] * yElements[yi + yStride * j];
                                    }
                                    zElements[zidx] = alpha * sum;
                                    zidx += zStride;
                                }
                            } else {
                                for (int i = firstRow; i < lastRow; i++) {
                                    int high = rowPointers[i + 1];
                                    int sum = 0;
                                    for (int k = rowPointers[i]; k < high; k++) {
                                        int j = idx[k];
                                        sum += vals[k] * yElements[yi + yStride * j];
                                    }
                                    zElements[zidx] = alpha * sum + beta * zElements[zidx];
                                    zidx += zStride;
                                }
                            }
                        } else {
                            for (int i = firstRow; i < lastRow; i++) {
                                int high = rowPointers[i + 1];
                                long yElem = alpha * yElements[yi + yStride * i];
                                for (int k = rowPointers[i]; k < high; k++) {
                                    int j = idx[k];
                                    zElements[zi + zStride * j] += vals[k] * yElem;
                                }
                            }
                        }

                    }
                });
            }
            ConcurrencyUtils.waitForCompletion(futures);
        } else {
            int zidx = zi;
            if (!transposeA) {
                if (beta == 0.0) {
                    for (int i = 0; i < s; i++) {
                        int high = rowPointers[i + 1];
                        int sum = 0;
                        for (int k = rowPointers[i]; k < high; k++) {
                            int j = idx[k];
                            sum += vals[k] * yElements[yi + yStride * j];
                        }
                        zElements[zidx] = alpha * sum;
                        zidx += zStride;
                    }
                } else {
                    for (int i = 0; i < s; i++) {
                        int high = rowPointers[i + 1];
                        int sum = 0;
                        for (int k = rowPointers[i]; k < high; k++) {
                            int j = idx[k];
                            sum += vals[k] * yElements[yi + yStride * j];
                        }
                        zElements[zidx] = alpha * sum + beta * zElements[zidx];
                        zidx += zStride;
                    }
                }
            } else {
                for (int i = 0; i < s; i++) {
                    int high = rowPointers[i + 1];
                    long yElem = alpha * yElements[yi + yStride * i];
                    for (int k = rowPointers[i]; k < high; k++) {
                        int j = idx[k];
                        zElements[zi + zStride * j] += vals[k] * yElem;
                    }
                }
            }
        }

        return z;
    }

    @Override
    public LongMatrix2D zMult(LongMatrix2D B, LongMatrix2D C) {
        int m = rows;
        int n = columns;
        int p = B.columns();
        if (C == null)
            C = new DenseLongMatrix2D(m, p);

        if (B.rows() != n)
            throw new IllegalArgumentException("Matrix2D inner dimensions must agree:" + toStringShort() + ", "
                    + B.toStringShort());
        if (C.rows() != m || C.columns() != p)
            throw new IllegalArgumentException("Incompatible result matrix: " + toStringShort() + ", "
                    + B.toStringShort() + ", " + C.toStringShort());
        if (this == C || B == C)
            throw new IllegalArgumentException("Matrices must not be identical");

        // cache views
        final LongMatrix1D[] Brows = new LongMatrix1D[n];
        for (int i = n; --i >= 0;)
            Brows[i] = B.viewRow(i);
        final LongMatrix1D[] Crows = new LongMatrix1D[m];
        for (int i = m; --i >= 0;)
            Crows[i] = C.viewRow(i);

        final cern.jet.math.tlong.LongPlusMultSecond fun = cern.jet.math.tlong.LongPlusMultSecond.plusMult(0);

        final int[] indexesE = columnIndexes.elements();
        final long[] valuesE = values.elements();
        for (int i = rowPointers.length - 1; --i >= 0;) {
            int low = rowPointers[i];
            for (int k = rowPointers[i + 1]; --k >= low;) {
                int j = indexesE[k];
                fun.multiplicator = valuesE[k];
                Crows[i].assign(Brows[j], fun);
            }
        }
        return C;
    }

    @Override
    public LongMatrix2D zMult(LongMatrix2D B, LongMatrix2D C, final int alpha, int beta, final boolean transposeA,
            boolean transposeB) {
        if (transposeB)
            B = B.viewDice();
        int m = rows;
        int n = columns;
        if (transposeA) {
            m = columns;
            n = rows;
        }
        int p = B.columns();
        boolean ignore = (C == null);
        if (C == null)
            C = new DenseLongMatrix2D(m, p);

        if (B.rows() != n)
            throw new IllegalArgumentException("Matrix2D inner dimensions must agree:" + toStringShort() + ", "
                    + (transposeB ? B.viewDice() : B).toStringShort());
        if (C.rows() != m || C.columns() != p)
            throw new IllegalArgumentException("Incompatible result matrix: " + toStringShort() + ", "
                    + (transposeB ? B.viewDice() : B).toStringShort() + ", " + C.toStringShort());
        if (this == C || B == C)
            throw new IllegalArgumentException("Matrices must not be identical");

        if (!ignore)
            C.assign(cern.jet.math.tlong.LongFunctions.mult(beta));

        // cache views
        final LongMatrix1D[] Brows = new LongMatrix1D[n];
        for (int i = n; --i >= 0;)
            Brows[i] = B.viewRow(i);
        final LongMatrix1D[] Crows = new LongMatrix1D[m];
        for (int i = m; --i >= 0;)
            Crows[i] = C.viewRow(i);

        final cern.jet.math.tlong.LongPlusMultSecond fun = cern.jet.math.tlong.LongPlusMultSecond.plusMult(0);

        final int[] indexesE = columnIndexes.elements();
        final long[] valuesE = values.elements();
        for (int i = rowPointers.length - 1; --i >= 0;) {
            int low = rowPointers[i];
            for (int k = rowPointers[i + 1]; --k >= low;) {
                int j = indexesE[k];
                fun.multiplicator = valuesE[k] * alpha;
                if (!transposeA)
                    Crows[i].assign(Brows[j], fun);
                else
                    Crows[j].assign(Brows[i], fun);
            }
        }
        return C;
    }
}
