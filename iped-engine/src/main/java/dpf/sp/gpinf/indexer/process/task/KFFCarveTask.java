package dpf.sp.gpinf.indexer.process.task;

import java.io.BufferedInputStream;
import java.io.File;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.tika.mime.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dpf.sp.gpinf.indexer.parsers.util.LedHashes;
import dpf.sp.gpinf.indexer.util.HashValue;
import dpf.sp.gpinf.indexer.util.IOUtil;
import iped3.IItem;
import iped3.IHashValue;

public class KFFCarveTask extends BaseCarveTask {
    
    private static Logger logger = LoggerFactory.getLogger(KFFCarveTask.class);
    /**
     * Nome da tarefa.
     */
    private static final String taskName = "KFF Carving"; //$NON-NLS-1$

    /**
     * Indica se a tarefa está habilitada ou não.
     */
    private static boolean taskEnabled = false;

    /**
     * Indicador de inicialização, para controle de sincronização entre instâncias
     * da classe.
     */
    private static final AtomicBoolean init = new AtomicBoolean(false);

    /**
     * Objeto estático para sincronizar finalização.
     */
    private static final AtomicBoolean finished = new AtomicBoolean(false);

    /**
     * Contador de arquivos recuperados.
     */
    private static final AtomicInteger numCarvedItems = new AtomicInteger();

    /**
     * Contador de bytes com hash calculado.
     */
    private static final AtomicLong bytesHashed = new AtomicLong();

    /**
     * Contador do total de blocos de 512 processados.
     */
    private static final AtomicLong num512total = new AtomicLong();

    /**
     * Contador do total de hits em blocos de 512 bytes.
     */
    private static final AtomicLong num512hit = new AtomicLong();

    /**
     * Digest utilizado para cálculo do MD5.
     */
    private MessageDigest digest = null;

    /**
     * Lista de hashes dos 512 bytes iniciais.
     */
    private static IHashValue[] md5_512;

    @Override
    public boolean isEnabled() {
        return taskEnabled;
    }

    public static void setEnabled(boolean enabled) {
        taskEnabled = enabled;
    }

    /**
     * Inicializa tarefa.
     */
    public void init(Properties confParams, File confDir) throws Exception {
        synchronized (init) {
            if (!init.get()) {
                String value = confParams.getProperty("enableKFFCarving"); //$NON-NLS-1$
                if (value != null && value.trim().equalsIgnoreCase("true")) { //$NON-NLS-1$
                    if (LedKFFTask.kffItems != null) {
                        md5_512 = LedHashes.hashMap.get("md5-512"); //$NON-NLS-1$
                        logger.info("Loaded Hashes: " + md5_512.length); //$NON-NLS-1$
                        taskEnabled = true;
                    } else {
                        logger.error("LED database must be loaded to enable KFFCarving."); //$NON-NLS-1$
                    }
                }
                logger.info(taskEnabled ? "Task enabled." : "Task disabled."); //$NON-NLS-1$ //$NON-NLS-2$
                init.set(true);
            }
        }
        if (taskEnabled)
            digest = MessageDigest.getInstance("MD5"); //$NON-NLS-1$
    }

    /**
     * Finaliza a tarefa.
     */
    public void finish() throws Exception {
        synchronized (finished) {
            if (taskEnabled && !finished.get()) {
                md5_512 = null;
                kffCarved.clear();
                finished.set(true);
                NumberFormat nf = new DecimalFormat("#,##0"); //$NON-NLS-1$
                logger.info("Carved files: " + nf.format(numCarvedItems.get())); //$NON-NLS-1$
                logger.info("512 blocks (Hits / Total): " + nf.format(num512hit.get()) + " / " //$NON-NLS-1$ //$NON-NLS-2$
                        + nf.format(num512total.get()));
                logger.info("Bytes hashes: " + nf.format(bytesHashed.get())); //$NON-NLS-1$
            }
        }
    }

    protected void process(IItem evidence) throws Exception {
        // Verifica se está desabilitado e se o tipo de arquivo é tratado
        if (!taskEnabled || caseData.isIpedReport() || !isAcceptedType(evidence.getMediaType())
                || !isToProcess(evidence))
            return;

        byte[] buf512 = new byte[512];
        byte[] buf64K = new byte[65536 - buf512.length];
        BufferedInputStream is = null;

        int cntCarvedItems = 0;
        long cnt512hit = 0;
        long cnt512total = 0;
        long cntBytesHashed = 0;
        Set<Long> offsets = null;
        try {
            long offset = 0;
            int read512 = 0;
            is = evidence.getBufferedStream();
            while ((read512 = is.read(buf512)) > 0) {
                if (read512 != buf512.length)
                    break;
                cnt512total++;
                boolean empty = true;
                byte first = buf512[0];
                for (int i = 1; i < read512; i++) {
                    if (buf512[i] != first) {
                        empty = false;
                        break;
                    }
                }
                if (!empty) {
                    digest.update(buf512, 0, read512);
                    cntBytesHashed += read512;
                    IHashValue hash512 = new HashValue(digest.digest());
                    if (Arrays.binarySearch(md5_512, hash512) >= 0) {
                        cnt512hit++;
                        is.mark(65536);
                        int read64K = is.read(buf64K);
                        is.reset();
                        if (read64K == buf64K.length) {
                            cntBytesHashed += read512 + read64K;
                            digest.update(buf512, 0, read512);
                            digest.update(buf64K, 0, read64K);
                            IHashValue hash64K = new HashValue(digest.digest());
                            KffItem kffItem = KffItem.kffSearch(LedKFFTask.kffItems, hash64K);
                            if (kffItem != null) {
                                String name = "CarvedKff-" + offset; //$NON-NLS-1$
                                String ext = kffItem.getExt();
                                if (ext != null)
                                    name += '.' + ext.toLowerCase();
                                IItem carvedItem = createCarvedFile(evidence, offset, kffItem.getLength(), name, null);
                                if (carvedItem != null) {
                                    carvedItem.setExtraAttribute("kffCarvedMD5", kffItem.getMD5().toString()); //$NON-NLS-1$
                                    cntCarvedItems++;
                                    if (offsets == null) {
                                        offsets = new HashSet<Long>();
                                        synchronized (kffCarved) {
                                            kffCarved.put(evidence, offsets);
                                        }
                                    }
                                    offsets.add(offset);
                                    addOffsetFile(carvedItem, evidence);
                                }
                            }
                        }
                    }
                }
                offset += read512;
            }
        } catch (Exception e) {
            e.printStackTrace();
            logger.warn("Error KFFCarving on: " + evidence.getPath() + " : " + e); //$NON-NLS-1$ //$NON-NLS-2$
        } finally {
            IOUtil.closeQuietly(is);
        }
        numCarvedItems.addAndGet(cntCarvedItems);
        num512hit.addAndGet(cnt512hit);
        num512total.addAndGet(cnt512total);
        bytesHashed.addAndGet(cntBytesHashed);
    }

    private static boolean isAcceptedType(MediaType mediaType) {
        return mediaType.getBaseType().equals(UNALLOCATED_MIMETYPE) || mediaType.getBaseType().equals(mtPageFile)
                || mediaType.getBaseType().equals(mtDiskImage) || mediaType.getBaseType().equals(mtUnknown)
                || mediaType.getBaseType().equals(mtVdi) || mediaType.getBaseType().equals(mtVhd)
                || mediaType.getBaseType().equals(mtVmdk) || mediaType.getBaseType().equals(mtVolumeShadow);
    }
}