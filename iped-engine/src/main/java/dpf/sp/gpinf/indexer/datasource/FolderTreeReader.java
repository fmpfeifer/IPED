/*
 * Copyright 2012-2014, Luis Filipe da Cruz Nassif
 * 
 * This file is part of Indexador e Processador de Evidências Digitais (IPED).
 *
 * IPED is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * IPED is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IPED.  If not, see <http://www.gnu.org/licenses/>.
 */
package dpf.sp.gpinf.indexer.datasource;

import gpinf.dev.data.DataSource;
import gpinf.dev.data.Item;
import gpinf.dev.data.FileGroup;
import iped3.ICaseData;
import iped3.IItem;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.util.Date;
import java.util.LinkedList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dpf.sp.gpinf.indexer.CmdLineArgs;
import dpf.sp.gpinf.indexer.WorkerProvider;
import dpf.sp.gpinf.indexer.util.Util;

public class FolderTreeReader extends DataSourceReader {

    private static Logger LOGGER = LoggerFactory.getLogger(FolderTreeReader.class);

    public static final String FS_OWNER = "fileSystemOwner"; //$NON-NLS-1$

    private File rootFile;
    private String evidenceName;
    private CmdLineArgs args;

    public FolderTreeReader(ICaseData caseData, File output, boolean listOnly) {
        super(caseData, output, listOnly);
    }

    public int read(File file) throws Exception {

        args = (CmdLineArgs) caseData.getCaseObject(CmdLineArgs.class.getName());

        rootFile = file;
        evidenceName = getEvidenceName(file);
        dataSource = new DataSource(file);
        dataSource.setName(evidenceName);

        new FolderVisitor().walk(file);

        return 0;

    }

    private IItem getEvidence(Path path, BasicFileAttributes attr) {
        if (listOnly) {
            caseData.incDiscoveredEvidences(1);
            caseData.incDiscoveredVolume(attr.size());
            return null;

        } else {
            File file = path.toFile();
            IItem item = new Item();
            item.setName(file.getName());
            if (file.equals(rootFile)) {
                item.setName(evidenceName);
            }
            item.setDataSource(dataSource);
            try {
                String relativePath = Util.getRelativePath(output, file);
                item.setExportedFile(relativePath);
                item.setFile(file);
            } catch (InvalidPathException e) {
                LOGGER.error("File content will not be processed " + e.toString()); //$NON-NLS-1$
            }

            String path1 = file.getAbsolutePath().replace(rootFile.getAbsolutePath(), evidenceName);
            item.setPath(path1);

            // evidenceFile.setType(new UnknownFileType(evidenceFile.getExt()));

            if (args.isAddowner())
                try {
                    UserPrincipal owner = Files.getOwner(path);
                    if (owner != null)
                        item.setExtraAttribute(FS_OWNER, owner.toString());

                } catch (IOException e) {
                    e.printStackTrace();
                }

            return item;
        }
    }

    class FolderVisitor implements FileVisitor<Path> {

        private LinkedList<Integer> parentIds = new LinkedList<Integer>();

        public void walk(File file) throws IOException {
            Path startingDir = file.toPath();
            Files.walkFileTree(startingDir, this);
        }

        @Override
        public FileVisitResult visitFile(Path path, BasicFileAttributes attr) {
            if (Thread.interrupted()) {
                return FileVisitResult.TERMINATE;
            }

            IItem item = getEvidence(path, attr);
            if (item != null) {
                if (!parentIds.isEmpty()) {
                    item.setParentId(parentIds.getLast());
                    item.addParentIds(parentIds);
                } else {
                    item.setRoot(true);
                }

                if (attr.isDirectory()) {
                    item.setIsDir(true);
                }

                item.setAccessDate(new Date(attr.lastAccessTime().toMillis()));
                item.setCreationDate(new Date(attr.creationTime().toMillis()));
                item.setModificationDate(new Date(attr.lastModifiedTime().toMillis()));
                item.setLength(attr.size());

                try {
                    caseData.addItem(item);

                } catch (InterruptedException e) {
                    return FileVisitResult.TERMINATE;
                }
                
                if (attr.isDirectory()) {
                    //must getId() after caseData.addItem(), it could set item id to previous id with --continue
                    parentIds.addLast(item.getId());
                }
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attr) throws IOException {

            if (this.visitFile(path, attr).equals(FileVisitResult.TERMINATE)) {
                return FileVisitResult.TERMINATE;
            }

            if (attr.isSymbolicLink() || attr.isOther()) { // pula links simbólicos e NTFS junctions
                parentIds.pollLast();
                return FileVisitResult.SKIP_SUBTREE;

            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path path, IOException exception) throws IOException {

            parentIds.pollLast();

            if (exception != null) {
                System.err.println(new Date() + "\t[WARN]\t" + "Directory ignored: " + path.toFile().getAbsolutePath() //$NON-NLS-1$ //$NON-NLS-2$
                        + " " + exception.toString()); //$NON-NLS-1$
            }

            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path path, IOException exception) throws IOException {

            if (exception != null) {
                System.err.println(new Date() + "\t[WARN]\t" + "File ignored: " + path.toFile().getAbsolutePath() + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + exception.toString());
            }

            return FileVisitResult.CONTINUE;
        }

    }

    @Override
    public boolean isSupported(File datasource) {
        return true;
    }

}
