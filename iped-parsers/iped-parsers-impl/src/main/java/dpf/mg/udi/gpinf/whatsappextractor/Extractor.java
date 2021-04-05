package dpf.mg.udi.gpinf.whatsappextractor;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import dpf.sp.gpinf.indexer.util.DBList;

public abstract class Extractor {
    protected final File databaseFile;
    protected final WAContactsDirectory contacts;
    protected WAAccount account;

    protected Extractor(File databaseFile, WAContactsDirectory contacts, WAAccount account) {
        this.databaseFile = databaseFile;
        this.contacts = contacts;
        this.account = account;
    }

    public File getDatabaseFile() {
        return databaseFile;
    }

    public abstract DBList<Chat> extractChatList(Connection conn) throws WAExtractorException;

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
    }
}
