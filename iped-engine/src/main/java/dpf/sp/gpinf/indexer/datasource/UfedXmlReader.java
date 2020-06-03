package dpf.sp.gpinf.indexer.datasource;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TimeZone;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.commons.io.IOUtils;
import org.apache.tika.metadata.Message;
import org.apache.tika.mime.MediaType;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;

import dpf.mg.udi.gpinf.whatsappextractor.WhatsAppParser;
import dpf.sp.gpinf.indexer.Messages;
import dpf.sp.gpinf.indexer.config.ConfigurationManager;
import dpf.sp.gpinf.indexer.config.UFEDReaderConfig;
import dpf.sp.gpinf.indexer.parsers.ufed.UFEDChatParser;
import dpf.sp.gpinf.indexer.process.IndexItem;
import dpf.sp.gpinf.indexer.util.IOUtil;
import dpf.sp.gpinf.indexer.util.MetadataInputStreamFactory;
import dpf.sp.gpinf.indexer.util.SimpleHTMLEncoder;
import dpf.sp.gpinf.indexer.util.UFDRInputStreamFactory;
import dpf.sp.gpinf.indexer.util.UFEDXMLWrapper;
import dpf.sp.gpinf.indexer.util.Util;
import dpf.sp.gpinf.indexer.util.ZipFile4j;
import gpinf.dev.data.DataSource;
import gpinf.dev.data.Item;
import iped3.ICaseData;
import iped3.IItem;
import iped3.datasource.IDataSource;
import iped3.util.ExtraProperties;
import iped3.util.MediaTypes;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;

public class UfedXmlReader extends DataSourceReader {

    private static final String[] HEADER_STRINGS = { "project id", "extractionType", "sourceExtractions" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String AVATAR_PATH_META = ExtraProperties.UFED_META_PREFIX + "contactphoto_extracted_path"; //$NON-NLS-1$
    private static final String ATTACH_PATH_META = ExtraProperties.UFED_META_PREFIX + "attachment_extracted_path"; //$NON-NLS-1$
    private static final String EMAIL_ATTACH_KEY = ExtraProperties.UFED_META_PREFIX + "email_attach_names"; //$NON-NLS-1$
    private static final String UFED_ID = ExtraProperties.UFED_META_PREFIX + "id"; //$NON-NLS-1$

    public static final String UFED_MIME_PREFIX = MediaTypes.UFED_MIME_PREFIX;
    public static final String UFED_EMAIL_MIME = MediaTypes.UFED_EMAIL_MIME.toString();

    File root, ufdrFile;
    ZipFile4j ufdr;
    UFDRInputStreamFactory uisf;
    IItem rootItem;
    IItem decodedFolder;
    HashMap<String, IItem> pathToParent = new HashMap<>();

    public UfedXmlReader(ICaseData caseData, File output, boolean listOnly) {
        super(caseData, output, listOnly);
    }

    @Override
    public boolean isSupported(File datasource) {

        InputStream xmlReport = lookUpXmlReportInputStream(datasource);
        IOUtil.closeQuietly(xmlReport);
        // IOUtil.closeQuietly(ufdr);

        if (xmlReport != null)
            return true;

        return false;
    }

    private InputStream getXmlInputStream(File file) {
        if (file.getName().toLowerCase().endsWith(".xml")) { //$NON-NLS-1$
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), "UTF-8")) { //$NON-NLS-1$
                char[] cbuf = new char[1024];
                int off = 0, i = 0;
                while (off < cbuf.length && (i = reader.read(cbuf, off, cbuf.length - off)) != -1)
                    off += i;
                String header = new String(cbuf, 0, off);
                for (String str : HEADER_STRINGS)
                    if (!header.contains(str))
                        return null;

                return new FileInputStream(file);

            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else if (file.getName().toLowerCase().endsWith(".ufdr")) {
            try {
                ufdrFile = file;
                // File part1 = new File(file.getAbsolutePath().substring(0,
                // file.getAbsolutePath().lastIndexOf('.')) + ".z01");
                // SequenceSeekableByteChannel ssbc = new
                // SequenceSeekableByteChannel(Files.newByteChannel(part1.toPath()),
                // Files.newByteChannel(ufdrFile.toPath()));
                ufdr = new ZipFile4j(file);
                FileHeader xml = ufdr.getFileHeader("report.xml");
                if (xml == null)
                    xml = ufdr.getFileHeader("Report.xml");
                return ufdr.getInputStream(xml);

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    private InputStream lookUpXmlReportInputStream(File root) {
        if (root.isFile())
            return getXmlInputStream(root);
        File[] files = root.listFiles();
        if (files != null) {
            for (File file : files) {
                InputStream is = getXmlInputStream(file);
                if (is != null)
                    return is;
            }
        }
        return null;
    }

    @Override
    public int read(File root) throws Exception {

        this.root = root;
        addRootItem();
        addVirtualDecodedFolder();
        InputStream xmlStream = lookUpXmlReportInputStream(root);

        configureParsers();

        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setNamespaceAware(true);
        SAXParser saxParser = spf.newSAXParser();
        XMLReader xmlReader = saxParser.getXMLReader();
        xmlReader.setContentHandler(new XMLContentHandler());
        xmlReader.setErrorHandler(new XMLErrorHandler());
        xmlReader.parse(new InputSource(new UFEDXMLWrapper(xmlStream)));

        IOUtil.closeQuietly(xmlStream);
        // IOUtil.closeQuietly(ufdr);

        return 0;
    }

    private void configureParsers() {

        UFEDReaderConfig ufedReaderConfig = (UFEDReaderConfig) ConfigurationManager.getInstance()
                .findObjects(UFEDReaderConfig.class).iterator().next();

        if (ufedReaderConfig.getPhoneParsersToUse().equals("internal")) { //$NON-NLS-1$
            UFEDChatParser.setSupportedTypes(Collections.singleton(UFEDChatParser.UFED_CHAT_MIME));

        } else if (ufedReaderConfig.getPhoneParsersToUse().equals("external")) //$NON-NLS-1$
            WhatsAppParser.setSupportedTypes(Collections.EMPTY_SET);
    }

    private void addRootItem() throws InterruptedException {

        if (listOnly)
            return;

        String evidenceName = getEvidenceName(root);
        IDataSource evidenceSource = new DataSource(root);
        evidenceSource.setName(evidenceName);

        rootItem = new Item();
        rootItem.setRoot(true);
        rootItem.setDataSource(evidenceSource);
        rootItem.setPath(evidenceName);
        rootItem.setName(evidenceName);
        rootItem.setHasChildren(true);
        if (root.getName().endsWith(".ufdr")) {
            rootItem.setLength(root.length());
            rootItem.setSumVolume(false);
        }
        rootItem.setHash(""); //$NON-NLS-1$

        pathToParent.put(rootItem.getPath(), rootItem);

        caseData.incDiscoveredEvidences(1);
        caseData.addItem(rootItem);
    }

    private void addVirtualDecodedFolder() throws InterruptedException {

        if (listOnly)
            return;

        decodedFolder = new Item();
        decodedFolder.setName("_DecodedData"); //$NON-NLS-1$
        decodedFolder.setParent(rootItem);
        decodedFolder.setPath(rootItem.getPath() + "/" + decodedFolder.getName()); //$NON-NLS-1$
        decodedFolder.setIsDir(true);
        decodedFolder.setHasChildren(true);
        decodedFolder.setHash(""); //$NON-NLS-1$

        pathToParent.put(decodedFolder.getPath(), decodedFolder);

        caseData.incDiscoveredEvidences(1);
        caseData.addItem(decodedFolder);
    }

    private class XMLErrorHandler implements ErrorHandler {

        @Override
        public void warning(SAXParseException exception) throws SAXException {
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            exception.printStackTrace();
        }
    }

    private class XMLContentHandler implements ContentHandler {

        StringBuilder chars = new StringBuilder();

        HashMap<String, String> extractionInfoMap = new HashMap<String, String>();

        String df2Pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS"; //$NON-NLS-1$
        DateFormat df1 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"); //$NON-NLS-1$
        DateFormat df2 = new SimpleDateFormat(df2Pattern);

        ArrayList<XmlNode> nodeSeq = new ArrayList<>();
        ArrayList<Item> itemSeq = new ArrayList<>();

        HashSet<String> elements = new HashSet<>();
        HashSet<String> types = new HashSet<>();

        HashMap<String, IItem> ids = new HashMap<>();

        private class XmlNode {
            String element;
            HashMap<String, String> atts = new HashMap<>();

            private XmlNode(String element, Attributes atts) {
                this.element = element;
                for (int i = 0; i < atts.getLength(); i++) {
                    this.atts.put(atts.getQName(i), atts.getValue(i));
                }
            }
        }

        HashSet<String> ignoreAttrs = new HashSet<>(Arrays.asList("type", //$NON-NLS-1$
                "path", //$NON-NLS-1$
                "size", //$NON-NLS-1$
                "deleted", //$NON-NLS-1$
                "deleted_state" //$NON-NLS-1$
        ));

        HashSet<String> ignoreNameAttrs = new HashSet<>(Arrays.asList("Tags", //$NON-NLS-1$
                "Local Path", //$NON-NLS-1$
                "CreationTime", //$NON-NLS-1$
                "ModifyTime", //$NON-NLS-1$
                "AccessTime", //$NON-NLS-1$
                "CoreFileSystemFileSystemNodeCreationTime", //$NON-NLS-1$
                "CoreFileSystemFileSystemNodeModifyTime", //$NON-NLS-1$
                "CoreFileSystemFileSystemNodeLastAccessTime", //$NON-NLS-1$
                "UserMapping" //$NON-NLS-1$
        ));

        HashSet<String> mergeInParentNode = new HashSet<>(Arrays.asList("Party", //$NON-NLS-1$
                "PhoneNumber", //$NON-NLS-1$
                "EmailAddress", //$NON-NLS-1$
                "Coordinate", //$NON-NLS-1$
                "Organization", //$NON-NLS-1$
                "UserID", //$NON-NLS-1$
                "ContactPhoto", //$NON-NLS-1$
                "StreetAddress" //$NON-NLS-1$
        ));

        @Override
        public void setDocumentLocator(Locator locator) {
            // TODO Auto-generated method stub

        }

        @Override
        public void startDocument() throws SAXException {
            // TODO remover timezone da exibição? obter da linha de comando?
            df2.setTimeZone(TimeZone.getTimeZone("GMT")); //$NON-NLS-1$
            df1.setTimeZone(TimeZone.getTimeZone("GMT")); //$NON-NLS-1$
        }

        @Override
        public void endDocument() throws SAXException {
            /*
             * for(String s : elements) System.out.println("element: " + s); for(String s :
             * types) System.out.println("type: " + s);
             */
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            // TODO Auto-generated method stub

        }

        @Override
        public void endPrefixMapping(String prefix) throws SAXException {
            // TODO Auto-generated method stub

        }

        private IItem getParent(String path) throws SAXException {
            int idx = path.lastIndexOf('/');
            if (idx < 1)
                return rootItem;

            String parentPath = path.substring(0, idx);
            IItem parent = pathToParent.get(parentPath);
            if (parent != null)
                return parent;

            parent = new Item();
            parent.setName(parentPath.substring(parentPath.lastIndexOf('/') + 1));
            parent.setPath(parentPath);
            parent.setHasChildren(true);
            parent.setIsDir(true);
            // parent.setLength(0L);
            parent.setHash(""); //$NON-NLS-1$
            parent.setParent(getParent(parentPath));

            pathToParent.put(parentPath, parent);

            try {
                caseData.incDiscoveredEvidences(1);
                caseData.addItem(parent);

            } catch (InterruptedException e) {
                throw new SAXException(e);
            }

            return parent;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {

            XmlNode node = new XmlNode(qName, atts);
            nodeSeq.add(node);

            if (!listOnly)
                elements.add(qName);

            if (qName.equals("extractionInfo")) { //$NON-NLS-1$
                String id = atts.getValue("id"); //$NON-NLS-1$
                String name = atts.getValue("name"); //$NON-NLS-1$
                extractionInfoMap.put(id, name);

            } else if (qName.equals("file")) { //$NON-NLS-1$
                String len = atts.getValue("size"); //$NON-NLS-1$
                Long size = null;
                if (len != null)
                    size = Long.valueOf(len.trim());

                if (listOnly) {
                    caseData.incDiscoveredEvidences(1);
                    caseData.incDiscoveredVolume(size);
                    return;
                }

                Item item = new Item();

                item.setLength(size);

                String fs = "/" + atts.getValue("fs"); //$NON-NLS-1$ //$NON-NLS-2$
                String path = rootItem.getName() + fs + atts.getValue("path"); //$NON-NLS-1$
                item.setPath(path);

                String name = path.substring(path.lastIndexOf('/') + 1);
                item.setName(name);

                item.setParent(getParent(path));

                boolean deleted = "deleted".equalsIgnoreCase(atts.getValue("deleted")); //$NON-NLS-1$ //$NON-NLS-2$
                item.setDeleted(deleted);

                fillCommonMeta(item, atts);
                itemSeq.add(item);

            } else if (qName.equals("model")) { //$NON-NLS-1$
                XmlNode prevNode = nodeSeq.get(nodeSeq.size() - 2);
                if (prevNode.element.equals("modelType")) { //$NON-NLS-1$
                    if (listOnly) {
                        caseData.incDiscoveredEvidences(1);
                        return;
                    }

                    Item item = new Item();

                    String type = atts.getValue("type"); //$NON-NLS-1$
                    String name = type + "_" + atts.getValue("id"); //$NON-NLS-1$ //$NON-NLS-2$
                    item.setName(name);
                    String path = decodedFolder.getPath() + "/" + type + "/" + name; //$NON-NLS-1$ //$NON-NLS-2$
                    item.setPath(path);
                    item.setParent(getParent(path));
                    item.setMediaType(MediaType.application(UFED_MIME_PREFIX + type));
                    if (caseData.containsReport()) {
                        // export metadata as content only if generating blind report
                        item.setInputStreamFactory(new MetadataInputStreamFactory(item.getMetadata()));
                    }
                    item.setHash(""); //$NON-NLS-1$

                    boolean deleted = "deleted".equalsIgnoreCase(atts.getValue("deleted_state")); //$NON-NLS-1$ //$NON-NLS-2$
                    item.setDeleted(deleted);

                    fillCommonMeta(item, atts);
                    itemSeq.add(item);

                } else if (prevNode.element.equals("modelField") || prevNode.element.equals("multiModelField")) { //$NON-NLS-1$ //$NON-NLS-2$

                    String type = atts.getValue("type"); //$NON-NLS-1$
                    if (listOnly) {
                        if (!mergeInParentNode.contains(type))
                            caseData.incDiscoveredEvidences(1);
                        return;
                    }

                    Item item = new Item();
                    IItem parent = itemSeq.get(itemSeq.size() - 1);

                    String name = type + "_" + atts.getValue("id"); //$NON-NLS-1$ //$NON-NLS-2$
                    String prevNameAtt = prevNode.atts.get("name"); //$NON-NLS-1$
                    if ("Location".equals(type) && ("FromPoint".equals(prevNameAtt) || "ToPoint".equals(prevNameAtt))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        name = prevNameAtt + "_" + name; //$NON-NLS-1$
                    item.setName(name);
                    item.setPath(parent.getPath() + "/" + name); //$NON-NLS-1$
                    item.setMediaType(MediaType.application(UFED_MIME_PREFIX + type));
                    item.setInputStreamFactory(new MetadataInputStreamFactory(item.getMetadata()));
                    item.setHash(""); //$NON-NLS-1$

                    item.setParent(parent);
                    if (!mergeInParentNode.contains(type))
                        parent.setHasChildren(true);

                    boolean deleted = "deleted".equalsIgnoreCase(atts.getValue("deleted_state")); //$NON-NLS-1$ //$NON-NLS-2$
                    item.setDeleted(deleted);

                    fillCommonMeta(item, atts);
                    itemSeq.add(item);
                }
            }

            chars = new StringBuilder();

        }

        private void fillCommonMeta(IItem item, Attributes atts) {
            if ("StreetAddress".equals(atts.getValue("type"))) //$NON-NLS-1$ //$NON-NLS-2$
                return;
            String extractionName = extractionInfoMap.get(atts.getValue("extractionId")); //$NON-NLS-1$
            item.getMetadata().add(ExtraProperties.UFED_META_PREFIX + "extractionName", extractionName); //$NON-NLS-1$

            for (int i = 0; i < atts.getLength(); i++) {
                String attName = atts.getQName(i);
                if (!ignoreAttrs.contains(attName)) {
                    String value = atts.getValue(i);
                    item.getMetadata().add(ExtraProperties.UFED_META_PREFIX + attName, value);
                }
            }
            if(item.getMetadata().get(UFED_ID) != null) {
                item.setIdInDataSource(item.getMetadata().get(UFED_ID));
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {

            XmlNode currentNode = nodeSeq.remove(nodeSeq.size() - 1);
            
            for(XmlNode node : nodeSeq) {
                if(node.element.equals("entityBookmarks")) { //$NON-NLS-1$
                    //currently there is no support for bookmarks
                    return;
                }
            }

            if (listOnly)
                return;

            String nameAttr = currentNode.atts.get("name"); //$NON-NLS-1$
            Item item = null;
            if (itemSeq.size() > 0)
                item = itemSeq.get(itemSeq.size() - 1);

            XmlNode parentNode = null;
            if (nodeSeq.size() > 0)
                parentNode = nodeSeq.get(nodeSeq.size() - 1);

            if (qName.equals("item")) { //$NON-NLS-1$
                if ("Tags".equals(nameAttr) && "Configuration".equals(chars.toString())) { //$NON-NLS-1$ //$NON-NLS-2$
                    item.setCategory(chars.toString());

                } else if ("Local Path".equals(nameAttr)) { //$NON-NLS-1$
                    setContent(item, chars.toString());

                } else if (!ignoreNameAttrs.contains(nameAttr) && !nameAttr.toLowerCase().startsWith("exif")) //$NON-NLS-1$
                    if (item != null && !chars.toString().trim().isEmpty())
                        item.getMetadata().add(ExtraProperties.UFED_META_PREFIX + nameAttr, chars.toString().trim());

            } else if (qName.equals("timestamp")) { //$NON-NLS-1$
                try {
                    String value = chars.toString().trim();
                    if (!value.isEmpty()) {
                        DateFormat df = df1;
                        if (df2Pattern.length() - 2 == value.length())
                            df = df2;
                        if (nameAttr.equals("CreationTime")) //$NON-NLS-1$
                            item.setCreationDate(df.parse(value));
                        else if (nameAttr.equals("ModifyTime")) //$NON-NLS-1$
                            item.setModificationDate(df.parse(value));
                        else if (nameAttr.equals("AccessTime")) //$NON-NLS-1$
                            item.setAccessDate(df.parse(value));
                        else
                            item.getMetadata().add(ExtraProperties.UFED_META_PREFIX + nameAttr, value);
                    }

                } catch (ParseException e) {
                    throw new SAXException(e);
                }
            } else if (qName.equals("value")) { //$NON-NLS-1$
                if (parentNode.element.equals("field") || parentNode.element.equals("multiField")) { //$NON-NLS-1$ //$NON-NLS-2$
                    String parentNameAttr = parentNode.atts.get("name"); //$NON-NLS-1$
                    if (!ignoreNameAttrs.contains(parentNameAttr)) {
                        String meta = ExtraProperties.UFED_META_PREFIX + parentNameAttr;
                        String type = currentNode.atts.get("type"); //$NON-NLS-1$
                        String value = chars.toString().trim();
                        DateFormat df = df1;
                        if (df2Pattern.length() - 2 == value.length())
                            df = df2;
                        if (type.equals("TimeStamp")) //$NON-NLS-1$
                            try {
                                item.getMetadata().add(meta, df.format(df.parse(value)));
                            } catch (ParseException e) {
                                e.printStackTrace();
                            }
                        else
                            if(item != null) item.getMetadata().add(meta, value);
                    }
                }
            } else if (qName.equals("targetid") && parentNode.element.equals("jumptargets")) { //$NON-NLS-1$ //$NON-NLS-2$
                item.getMetadata().add(ExtraProperties.UFED_META_PREFIX + parentNode.element, chars.toString().trim());

            } else if (qName.equals("file")) { //$NON-NLS-1$
                itemSeq.remove(itemSeq.size() - 1);
                try {
                    caseData.addItem(item);
                } catch (Exception e) {
                    throw new SAXException(e);
                }

            } else if (qName.equals("model") && ( //$NON-NLS-1$
            parentNode.element.equals("modelType") || //$NON-NLS-1$
                    parentNode.element.equals("modelField") || //$NON-NLS-1$
                    parentNode.element.equals("multiModelField"))) { //$NON-NLS-1$

                itemSeq.remove(itemSeq.size() - 1);
                String type = currentNode.atts.get("type"); //$NON-NLS-1$
                if ("Contact".equals(type) || "UserAccount".equals(type)) { //$NON-NLS-1$ //$NON-NLS-2$
                    createContactPreview(item);

                } else if ("Email".equals(type)) { //$NON-NLS-1$
                    createEmailPreview(item);

                } else if ("Attachment".equals(type)) { //$NON-NLS-1$
                    handleAttachment(item);
                    IItem parentItem = itemSeq.get(itemSeq.size() - 1);
                    if (parentItem.getMediaType().toString().contains("email")) //$NON-NLS-1$
                        parentItem.getMetadata().add(EMAIL_ATTACH_KEY, item.getName());
                } else if ("Chat".equals(type)) { //$NON-NLS-1$
                    String source = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Source"); //$NON-NLS-1$
                    if ("whatsapp".equalsIgnoreCase(source)) //$NON-NLS-1$
                        item.setMediaType(UFEDChatParser.UFED_CHAT_WA_MIME);
                    item.setExtraAttribute(IndexItem.TREENODE, "true"); //$NON-NLS-1$
                }
                if ("InstantMessage".equals(type) || "Email".equals(type)) { //$NON-NLS-1$ //$NON-NLS-2$
                    String date = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "TimeStamp"); //$NON-NLS-1$
                    item.getMetadata().remove(ExtraProperties.UFED_META_PREFIX + "TimeStamp"); //$NON-NLS-1$
                    item.getMetadata().set(ExtraProperties.MESSAGE_DATE, date);

                    String subject = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Subject"); //$NON-NLS-1$
                    item.getMetadata().remove(ExtraProperties.UFED_META_PREFIX + "Subject"); //$NON-NLS-1$
                    item.getMetadata().set(ExtraProperties.MESSAGE_SUBJECT, subject);

                    String body = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Body"); //$NON-NLS-1$
                    item.getMetadata().remove(ExtraProperties.UFED_META_PREFIX + "Body"); //$NON-NLS-1$
                    if (body == null) {
                        body = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Snippet"); //$NON-NLS-1$
                        item.getMetadata().remove(ExtraProperties.UFED_META_PREFIX + "Snippet"); //$NON-NLS-1$
                    }
                    item.getMetadata().set(ExtraProperties.MESSAGE_BODY, body);
                }
                if (mergeInParentNode.contains(type) && itemSeq.size() > 0) {
                    IItem parentItem = itemSeq.get(itemSeq.size() - 1);
                    if ("Party".equals(type)) { //$NON-NLS-1$
                        String role = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Role"); //$NON-NLS-1$
                        String parentNameAttr = parentNode.atts.get("name"); //$NON-NLS-1$
                        if (role == null || role.equals("General")) //$NON-NLS-1$
                            role = parentNameAttr;
                        if (role.equals("To") && (parentNameAttr.equals("Bcc") || parentNameAttr.equals("Cc"))) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            role = parentNameAttr;
                        if (role.equals("Parties")) //$NON-NLS-1$
                            role = "Participants"; //$NON-NLS-1$

                        String identifier = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Identifier"); //$NON-NLS-1$
                        String name = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Name"); //$NON-NLS-1$
                        String value = name == null || name.equals(identifier) ? identifier
                                : identifier == null ? name : name + "(" + identifier + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                        if (value != null) {
                            if ("From".equalsIgnoreCase(role)) //$NON-NLS-1$
                                parentItem.getMetadata().add(Message.MESSAGE_FROM, value);
                            else if ("To".equalsIgnoreCase(role)) //$NON-NLS-1$
                                parentItem.getMetadata().add(Message.MESSAGE_TO, value);
                            else if ("Cc".equalsIgnoreCase(role)) //$NON-NLS-1$
                                parentItem.getMetadata().add(Message.MESSAGE_CC, value);
                            else if ("Bcc".equalsIgnoreCase(role)) //$NON-NLS-1$
                                parentItem.getMetadata().add(Message.MESSAGE_BCC, value);
                            else
                                parentItem.getMetadata().add(ExtraProperties.UFED_META_PREFIX + role, value);
                        }

                        String isOwner = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "IsPhoneOwner"); //$NON-NLS-1$
                        if (value != null && Boolean.valueOf(isOwner) && parentItem.getMediaType().toString().contains("chat")) //$NON-NLS-1$
                            parentItem.getMetadata().add(UFEDChatParser.META_PHONE_OWNER, value);

                        if (Boolean.valueOf(isOwner) && "From".equals(role)) //$NON-NLS-1$
                            parentItem.getMetadata().add(UFEDChatParser.META_FROM_OWNER, Boolean.TRUE.toString());

                    } else if ("PhoneNumber".equals(type) || "EmailAddress".equals(type)) { //$NON-NLS-1$ //$NON-NLS-2$
                        String category = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Category"); //$NON-NLS-1$
                        String value = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Value"); //$NON-NLS-1$
                        if (value != null && !value.trim().isEmpty()) {
                            if (category != null)
                                value += " (" + category + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                            parentItem.getMetadata().add(ExtraProperties.UFED_META_PREFIX + type, value);
                        }

                    } else if ("Coordinate".equals(type)) { //$NON-NLS-1$
                        String lat = ExtraProperties.UFED_META_PREFIX + "Latitude"; //$NON-NLS-1$
                        String lon = ExtraProperties.UFED_META_PREFIX + "Longitude"; //$NON-NLS-1$
                        parentItem.getMetadata().add(lat, item.getMetadata().get(lat));
                        parentItem.getMetadata().add(lon, item.getMetadata().get(lon));

                    } else if ("Organization".equals(type)) { //$NON-NLS-1$
                        String value = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Name"); //$NON-NLS-1$
                        if (value != null) {
                            String position = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Position"); //$NON-NLS-1$
                            if (position != null)
                                value += " (" + position + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                            parentItem.getMetadata().add(ExtraProperties.UFED_META_PREFIX + type, value);
                        }
                    } else if ("UserID".equals(type)) { //$NON-NLS-1$
                        String value = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Value"); //$NON-NLS-1$
                        parentItem.getMetadata().add(ExtraProperties.UFED_META_PREFIX + type, value);

                    } else if ("ContactPhoto".equals(type)) { //$NON-NLS-1$
                        String avatarPath = item.getMetadata().get(AVATAR_PATH_META);
                        if (avatarPath != null) {
                            avatarPath = normalizePaths(avatarPath);
                            parentItem.getMetadata().add(AVATAR_PATH_META, avatarPath);
                        }
                    } else if ("StreetAddress".equals(type)) { //$NON-NLS-1$
                        for (String meta : item.getMetadata().names()) {
                            String[] vals = item.getMetadata().getValues(meta);
                            for (String val : vals)
                                parentItem.getMetadata().add(meta, val);
                        }
                    }
                } else
                    try {
                        caseData.incDiscoveredVolume(item.getLength());
                        caseData.addItem(item);

                    } catch (InterruptedException e) {
                        throw new SAXException(e);
                    }
            }

            chars = new StringBuilder();
            nameAttr = null;

        }

        private void setContent(Item item, String path) {
            item.setMediaType(null);
            item.setHash(null);
            item.setInputStreamFactory(null);
            if (path == null)
                return;
            path = normalizePaths(path);
            if (ufdrFile == null) {
                File file = new File(root, path);
                String relativePath = Util.getRelativePath(output, file);
                item.setExportedFile(relativePath);
                item.setFile(file);
                item.setLength(file.length());
            } else {
                if (uisf == null) {
                    uisf = new UFDRInputStreamFactory(ufdrFile.toPath());
                }
                FileHeader zae = null;
                try {
                    zae = ufdr.getFileHeader(path);
                } catch (ZipException e) {
                    e.printStackTrace();
                }
                if (zae != null) {
                    item.setLength(zae.getUncompressedSize());
                    item.setInputStreamFactory(uisf);
                    path = UFDRInputStreamFactory.UFDR_PATH_PREFIX + path;
                    String id = item.getIdInDataSource() != null ? item.getIdInDataSource() + "_" + path : path;
                    item.setIdInDataSource(id);
                }
            }
        }

        private void updateName(IItem item, String newName) {
            // prevents error DocValuesField is too large
            int maxNameSize = 4096;
            if (newName.length() > maxNameSize)
                newName = newName.substring(0, maxNameSize);
            item.setName(newName);
            item.setPath(item.getPath().substring(0, item.getPath().lastIndexOf('/') + 1) + newName);
        }

        private void handleAttachment(Item item) {
            String name = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Filename"); //$NON-NLS-1$
            if (name != null)
                updateName(item, name);
            String extracted_path = item.getMetadata().get(ATTACH_PATH_META);
            setContent(item, extracted_path);
            if (item.getFile() == null)
                try {
                    String ufedSize = item.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Size"); //$NON-NLS-1$
                    if (ufedSize != null)
                        item.setLength(Long.parseLong(ufedSize.trim()));
                } catch (NumberFormatException e) {
                    // ignore
                }
        }

        private String normalizePaths(String path) {
            path = path.replace('\\', '/');
            // workaround for ufed paths with spaces as prefixes
            String[] frags = path.split("/");
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (String frag : frags) {
                sb.append(frag.trim());
                if (++i < frags.length)
                    sb.append("/");
            }
            return sb.toString();
        }

        private File createEmailPreview(Item email) {
            File file = new File(output, "view/emails/view-" + email.getId() + ".html"); //$NON-NLS-1$ //$NON-NLS-2$
            file.getParentFile().mkdirs();
            try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) { //$NON-NLS-1$
                bw.write("<!DOCTYPE html>\n" //$NON-NLS-1$
                        + "<html>\n" //$NON-NLS-1$
                        + "<head>\n" //$NON-NLS-1$
                        + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n" //$NON-NLS-1$
                        + "</head>\n"); //$NON-NLS-1$
                bw.write("<body>"); //$NON-NLS-1$
                // bw.write("<body
                // style=\"background-color:white;text-align:left;font-family:arial;color:black;font-size:14px;margin:5px;\">\n");
                // //$NON-NLS-1$

                String[] ufedMetas = { ExtraProperties.UFED_META_PREFIX + "Subject", //$NON-NLS-1$
                        Message.MESSAGE_FROM, Message.MESSAGE_TO, Message.MESSAGE_CC, Message.MESSAGE_BCC,
                        ExtraProperties.UFED_META_PREFIX + "TimeStamp" //$NON-NLS-1$
                };
                String[] printHeaders = { Messages.getString("UfedXmlReader.Subject"), //$NON-NLS-1$
                        Messages.getString("UfedXmlReader.From"), Messages.getString("UfedXmlReader.To"), //$NON-NLS-1$ //$NON-NLS-2$
                        Messages.getString("UfedXmlReader.Cc"), Messages.getString("UfedXmlReader.Bcc"), //$NON-NLS-1$ //$NON-NLS-2$
                        Messages.getString("UfedXmlReader.Date") }; //$NON-NLS-1$
                for (int i = 0; i < printHeaders.length; i++) {
                    String[] values = email.getMetadata().getValues(ufedMetas[i]);
                    if (values.length > 0) {
                        bw.write("<b>" + printHeaders[i] + ":</b>"); //$NON-NLS-1$ //$NON-NLS-2$
                        for (String value : values)
                            bw.write(" " + SimpleHTMLEncoder.htmlEncode(value)); //$NON-NLS-1$
                        bw.write("<br>"); //$NON-NLS-1$
                    }
                }

                String[] attachNames = email.getMetadata().getValues(EMAIL_ATTACH_KEY);
                if (attachNames != null && attachNames.length > 0) {
                    bw.write("<b>" + Messages.getString("UfedXmlReader.Attachments") + " (" + attachNames.length //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            + "):</b><br>"); //$NON-NLS-1$
                    for (String attach : attachNames) {
                        bw.write(SimpleHTMLEncoder.htmlEncode(attach) + "<br>"); //$NON-NLS-1$
                    }
                }

                bw.write("<hr>"); //$NON-NLS-1$

                String bodyMeta = ExtraProperties.UFED_META_PREFIX + "Body"; //$NON-NLS-1$
                String body = email.getMetadata().get(bodyMeta);
                email.getMetadata().remove(bodyMeta);
                if (body == null)
                    body = email.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Snippet"); //$NON-NLS-1$
                if (body != null)
                    bw.write(body);

                bw.write("</body></html>"); //$NON-NLS-1$

            } catch (IOException e) {
                e.printStackTrace();
            }
            email.setMediaType(MediaType.parse(UFED_EMAIL_MIME));
            String relativePath = Util.getRelativePath(output, file);
            email.setExportedFile(relativePath);
            email.setFile(file);
            email.setLength(file.length());
            email.setInputStreamFactory(null);
            email.setHash(null);

            return file;
        }

        private File createContactPreview(Item contact) {

            String name = contact.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Name"); //$NON-NLS-1$
            if (name == null)
                name = contact.getMetadata().get(ExtraProperties.UFED_META_PREFIX + "Username"); //$NON-NLS-1$
            if (name != null) {
                name = contact.getName().substring(0, contact.getName().indexOf('_') + 1) + name;
                updateName(contact, name);
            }

            File file = new File(output, "view/contacts/view-" + contact.getId() + ".html"); //$NON-NLS-1$ //$NON-NLS-2$
            file.getParentFile().mkdirs();
            try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) { //$NON-NLS-1$

                bw.write("<!DOCTYPE html>\n" //$NON-NLS-1$
                        + "<html>\n" //$NON-NLS-1$
                        + "<head>\n" //$NON-NLS-1$
                        + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n" //$NON-NLS-1$
                        + "</head>\n" //$NON-NLS-1$
                        + "<body>\n"); //$NON-NLS-1$

                String avatarPath = contact.getMetadata().get(AVATAR_PATH_META);
                if (avatarPath != null) {
                    contact.getMetadata().remove(AVATAR_PATH_META);
                    byte[] bytes = null;
                    if (ufdr != null) {
                        FileHeader zae = null;
                        try {
                            zae = ufdr.getFileHeader(avatarPath);
                        } catch (ZipException e) {
                            e.printStackTrace();
                        }
                        if (zae != null)
                            try (InputStream is = ufdr.getInputStream(zae)) {
                                bytes = IOUtils.toByteArray(is);
                            } catch (ZipException e) {
                                e.printStackTrace();
                            }
                    } else {
                        File avatarFile = new File(avatarPath);
                        if (!avatarFile.isAbsolute())
                            avatarFile = new File(root, avatarPath);
                        if (avatarFile.exists())
                            bytes = Files.readAllBytes(avatarFile.toPath());
                    }
                    if (bytes != null) {
                        bw.write("<img src=\"data:image/jpg;base64," //$NON-NLS-1$
                                + dpf.mg.udi.gpinf.whatsappextractor.Util.encodeBase64(bytes)
                                + "\" width=\"150\"/><br>\n"); //$NON-NLS-1$
                        contact.setThumb(bytes);
                    }
                }
                String[] metas = contact.getMetadata().names();
                Arrays.sort(metas);
                for (String meta : metas) {
                    bw.write(SimpleHTMLEncoder.htmlEncode(meta) + ": "); //$NON-NLS-1$
                    String[] vals = contact.getMetadata().getValues(meta);
                    for (int i = 0; i < vals.length; i++) {
                        bw.write(SimpleHTMLEncoder.htmlEncode(vals[i]));
                        if (i != vals.length - 1)
                            bw.write(" | "); //$NON-NLS-1$
                    }
                    bw.write("<br>"); //$NON-NLS-1$
                }
                bw.write("</body></html>"); //$NON-NLS-1$

            } catch (IOException e) {
                e.printStackTrace();
            }
            String relativePath = Util.getRelativePath(output, file);
            contact.setExportedFile(relativePath);
            contact.setFile(file);
            contact.setLength(file.length());
            contact.setInputStreamFactory(null);
            contact.setHash(null);

            return file;
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (listOnly)
                return;
            chars.append(ch, start, length);
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            // TODO Auto-generated method stub

        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            // TODO Auto-generated method stub

        }

        @Override
        public void skippedEntity(String name) throws SAXException {
            // TODO Auto-generated method stub

        }

    }

}
