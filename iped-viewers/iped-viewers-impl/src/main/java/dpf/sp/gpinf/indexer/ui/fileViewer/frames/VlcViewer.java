package dpf.sp.gpinf.indexer.ui.fileViewer.frames;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import iped3.io.IStreamSource;
import uk.co.caprica.vlcj.media.callback.seekable.SeekableCallbackMedia;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent;

/**
 * Embedded VLC Viewer for IPED. There is a single instance of this class, as using multiple player
 * instances is not officially supported by VLCJ.
 * VLC 64bits installation is required for this to work. If it is installed, VLCJ should be able to 
 * find and use it.
 * 
 * @author Fabio Melo Pfeifer <pfeifer.fmp@dpf.gov.br>
 *
 */
public class VlcViewer extends Viewer {
    
    /** 
     * Use CallbackMediaPlayerComponent for direct rendering (instead of using a 
     * heavy window component). This might be slower, but is more stable and more
     * compatible with swing.
     */
    private CallbackMediaPlayerComponent mediaPlayerComponent = null;
    
    private JPanel contentPane = new JPanel(new BorderLayout());
    private JPanel controlsPane = new JPanel();
    private JButton playPauseButton = new JButton("Play");
    private JButton rewindButton = new JButton("Rewind");
    private JButton skipButton = new JButton("Skip");
    private StreamSourceCallbackMedia media = null;
    private boolean videoPlaying = false;

    private static final VlcViewer instance = new VlcViewer();

    public static VlcViewer detachAndGetInstance() {
        final JPanel panel = instance.getPanel();
        if (panel.getParent() != null) {
            try {
                SwingUtilities.invokeAndWait(new Runnable() {
                    public void run() {
                        panel.getParent().remove(panel);
                    }
                });
            } catch (InvocationTargetException | InterruptedException e) {
            }
        }
        return instance;
    }

    private VlcViewer() {
        super(new BorderLayout());
    }

    @Override
    public String getName() {
        return "VLC";
    }

    @Override
    public boolean isSupportedType(String contentType) {
        return contentType.startsWith("video") || contentType.startsWith("audio");
    }

    private void createMediaPlayerComponent() {
        mediaPlayerComponent = new CallbackMediaPlayerComponent();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                VlcViewer.instance.dispose();
            }
        });
    }

    @Override
    public void init() {
        if (mediaPlayerComponent == null) {
            createMediaPlayerComponent();
        }
        contentPane.setBackground(Color.BLACK);
        contentPane.add(mediaPlayerComponent, BorderLayout.CENTER);
        
        controlsPane.add(playPauseButton);
        controlsPane.add(rewindButton);
        controlsPane.add(skipButton);
        contentPane.add(controlsPane, BorderLayout.SOUTH);

        mediaPlayerComponent.mediaPlayer().events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void stopped(MediaPlayer mediaPlayer) {
                updatePlayPauseButtonText();
            }

            @Override
            public void playing(MediaPlayer mediaPlayer) {
                updatePlayPauseButtonText();
            }

            @Override
            public void paused(MediaPlayer mediaPlayer) {
                updatePlayPauseButtonText();

            }

            @Override
            public void finished(MediaPlayer mediaPlayer) {
                mediaPlayer.submit(new Runnable() {
                    @Override
                    public void run() {
                        if (media != null) {
                            media.reset();
                            mediaPlayer.media().prepare(media);
                        }
                        updatePlayPauseButtonText();
                    }
                });
            }

        });
        
        /* Just for debugging, should be removed later */
        mediaPlayerComponent.mediaPlayer().events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void mediaPlayerReady(MediaPlayer mediaPlayer) {
                System.out.println("MEDIA PLAYER READY...");
                System.out.println("     Track Information: " + mediaPlayer.media().info().tracks());
                System.out.println("    Title Descriptions: " + mediaPlayer.titles().titleDescriptions());
                System.out.println("    Video Descriptions: " + mediaPlayer.video().trackDescriptions());
                System.out.println("    Audio Descriptions: " + mediaPlayer.audio().trackDescriptions());
                System.out.println("Chapter Descriptions: " + mediaPlayer.chapters().allDescriptions());
                System.out.println();
            }
        });

        playPauseButton.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                if (mediaPlayerComponent.mediaPlayer().status().isPlaying()) {
                    mediaPlayerComponent.mediaPlayer().controls().pause();
                    updatePlayPauseButtonText();
                } else {
                    mediaPlayerComponent.mediaPlayer().controls().play();
                    updatePlayPauseButtonText();
                }
            }
        });

        rewindButton.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                mediaPlayerComponent.mediaPlayer().controls().skipTime(-10000);
            }
        });

        skipButton.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                mediaPlayerComponent.mediaPlayer().controls().skipTime(10000);
            }
        });

        getPanel().add(contentPane, BorderLayout.CENTER);
    }

    private void updatePlayPauseButtonText() {
        Runnable r = new Runnable() {
            public void run() {
                if (mediaPlayerComponent != null) {
                    if (mediaPlayerComponent.mediaPlayer().status().isPlaying()) {
                        playPauseButton.setText("Pause");
                    } else {
                        playPauseButton.setText("Play");
                    }
                }
            }
        };
        if (EventQueue.isDispatchThread()) {
            r.run();
        } else {
            EventQueue.invokeLater(r);
        }
    }
    
    @Override
    public void dispose() {
        if (mediaPlayerComponent != null) {
            mediaPlayerComponent.release();
        }
        mediaPlayerComponent = null;
    }

    @Override
    public void loadFile(IStreamSource content, String contentType, Set<String> highlightTerms) {
        videoPlaying = content != null && contentType != null && contentType.startsWith("video");
        loadFile(content, highlightTerms);
    }
    
    @Override
    public void loadFile(IStreamSource content, Set<String> highlightTerms) {
        try {
            if (content == null) {
                media = null;
                mediaPlayerComponent.mediaPlayer().controls().stop();
                mediaPlayerComponent.setVisible(false);
            } else {
                media = new StreamSourceCallbackMedia(content);
                mediaPlayerComponent.mediaPlayer().media().prepare(media);
                mediaPlayerComponent.setVisible(videoPlaying);
            }
            updatePlayPauseButtonText();
        } catch (Exception e) {
        }
    }

    @Override
    public void scrollToNextHit(boolean forward) {
    }

    private static class StreamSourceCallbackMedia extends SeekableCallbackMedia {

        private SeekableByteChannel channel;
        private long size;

        public StreamSourceCallbackMedia(IStreamSource streamSource) throws IOException {
            this.channel = streamSource.getSeekableByteChannel();
            this.size = channel.size();
        }
        
        public void reset() {
            try {
                channel.position(0L);
            } catch (IOException e) {
            }
        }

        @Override
        protected int onRead(byte[] buffer, int bufferSize) throws IOException {
            ByteBuffer buff = ByteBuffer.wrap(buffer, 0, bufferSize);
            return channel.read(buff);
        }

        @Override
        protected long onGetSize() {
            return size;
        }

        @Override
        protected boolean onOpen() {
            return true;
        }

        @Override
        protected boolean onSeek(long offset) {
            try {
                channel.position(offset);
            } catch (IOException e) {
                return false;
            }
            return true;
        }

        @Override
        protected void onClose() {
        }

    }

}
