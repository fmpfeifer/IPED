package dpf.sp.gpinf.indexer.util;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class DBList<E> extends AbstractList<E> implements AutoCloseable {
    
    /**
     * PreparedStatement with the query to be performed in the database.
     * This statement will be closed when the close() method is called.
     * This PreparedStatement should accept at least two parameters: the start of the data page
     * and the number of records in page. The follow example is for SQLite:
     * 
     * SELECT ROW_NUMBER() OVER (ORDER BY LastName) RowNum, FirstName, LastName FROM customers 
     * WHERE (RowNum > :1 AND RowNum <= :2) ORDER BY RowNum;
     * 
     * The parameters numbers 1 and 2 above must be passed to lowerBoundParam and upperBoundParam
     */
    private PreparedStatement selectStmt;
    
    /**
     * Number of parameter in the selectStmt of the lower bound of the data page
     */
    private int lowerBoundParam;
    
    /**
     * Number of parameter in the selectStmt of the upper bound of the data page
     */
    private int upperBoundParam;
    
    /**
     * Function to create an data item from a record of the database
     */
    private Function<ResultSet, E> itemProducer;
    
    /**
     * Cache with the current page of data
     */
    private List<E> pageCache;
    
    /**
     * Number of items in a data page
     */
    private static int PAGE_SIZE = 1000;
    
    /**
     * Start of current page
     */
    private int pageStart;
    
    /**
     * Size of current list
     */
    private int size;
    
    /**
     * Creates a new DBList.
     *  
     * @param selectStmt PreparedStatement with the query to be performed in the database.
     * This statement will be closed when the close() method is called.
     * This PreparedStatement should accept at least two parameters: the start of the data page
     * and the number of records in page. The follow example is for SQLite:
     * 
     * SELECT ROW_NUMBER() OVER (ORDER BY LastName) RowNum, FirstName, LastName FROM customers 
     * WHERE (RowNum > :1 AND RowNum <= :2) ORDER BY RowNum;
     * 
     * The parameters numbers 1 and 2 above must be passed to lowerBoundParam and upperBoundParam
     * 
     * @param countStmt PreparedStatement with the query to count the number of records.
     * This statement will be closed immediately after the constructor is executed. 
     * 
     * @param lowerBoundParam Number of parameter in the selectStmt of the lower bound of the data page
     * @param upperBoundParam Number of parameter in the selectStmt of the upper bound of the data page
     * @param itemProducer Function to create an data item from a record of the database
     * @throws SQLException
     */
    public DBList(PreparedStatement selectStmt, 
            PreparedStatement countStmt, 
            int lowerBoundParam,
            int upperBoundParam,
            Function<ResultSet, E> itemProducer) throws SQLException {
        this.selectStmt = selectStmt;
        this.lowerBoundParam = lowerBoundParam;
        this.upperBoundParam = upperBoundParam;
        this.pageStart = -1;
        this.itemProducer = itemProducer;
        this.pageCache = new ArrayList<>(PAGE_SIZE);
        this.size = execCountQuery(countStmt);
    }
    
    private int execCountQuery(PreparedStatement countStmt) throws SQLException {
        int result = -1;
        try (ResultSet rs = countStmt.executeQuery()) {
            if (rs.next() ) {
                result = rs.getInt(1);
            }
        }
        countStmt.close();
        return result;
    }
    
    private void loadPageIfNeeded(int index) throws SQLException {
        if (pageStart < 0 || index < pageStart || index >= pageStart + PAGE_SIZE) {
            pageStart = index - index % PAGE_SIZE;
            selectStmt.setInt(lowerBoundParam, pageStart);
            selectStmt.setInt(upperBoundParam, pageStart + PAGE_SIZE);
            pageCache.clear();
            try (ResultSet resultSet = selectStmt.executeQuery()) {
                while (resultSet.next()) {
                    pageCache.add(itemProducer.apply(resultSet));
                }
            }
        }
    }

    @Override
    public E get(int index) {
        try {
            loadPageIfNeeded(index);
            return pageCache.get(index % PAGE_SIZE);
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public void close() throws Exception {
        selectStmt.close();        
    }

}
