package com.voip.ui;

import com.voip.buffer.JitterBuffer;
import com.voip.client.AudioClient;
import com.voip.client.AudioPlayback;
import com.voip.protocol.AudioConstants;

import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import java.io.IOException;
import java.util.List;
import javax.swing.Timer;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/GUIForms/JFrame.java to edit this template
 */

/**
 *
 * @author ADMIN
 */
public class ListenForm extends javax.swing.JFrame {

    /**
     * Creates new form ListenForm
     */
    
    private boolean isPlaying = true;
    private AudioClient audioClient;
    private AudioPlayback audioPlayback;
    private JitterBuffer jitterBuffer;
    private Timer statsTimer;
    private Timer deviceCheckTimer;
    private List<Mixer.Info> availableSpeakers;

    public ListenForm() {
        initComponents();
        initializeControls();
        initializeSpeakerComboBox();
        startDeviceCheckTimer();
    }

    public ListenForm(String host, int port) throws IOException, LineUnavailableException {
        initComponents();
        initializeControls();
        initializeSpeakerComboBox();
        startDeviceCheckTimer();
        // Cac doi tuong nay phoi hop nhan goi, dem jitter va phat am thanh.
        jitterBuffer = new JitterBuffer(AudioConstants.JITTER_BUFFER_MS);
        lblJitterBuffer.setText("Jitter Buffer: " + jitterBuffer.getBufferSizeMs() + " ms");
        audioPlayback = new AudioPlayback(jitterBuffer);
        setupDeviceChangeListener();
        audioClient = new AudioClient(host, port, jitterBuffer, audioPlayback);
        audioClient.startClient();
        audioPlayback.setVolumePercent(sliderVolume.getValue());
        jLabel5.setText("Dang ket noi...");
        jLabel6.setText("Server: " + host + ":" + port);
        jLabel7.setText("Client UDP: " + audioClient.getPort());
        txtLog.append("Da ket noi toi " + host + ":" + port + "\n");
        statsTimer.start();
    }

    private void initializeControls() {
        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        jLabel5.setText("Chua ket noi");
        jLabel3.setText("NGHE AM THANH TRUC TIEP");

        sliderVolume.setValue(100);
        cboJitterBuffer.setSelectedItem(AudioConstants.JITTER_BUFFER_MS + "ms");
        cboJitterBuffer.addActionListener(event -> {
            if (jitterBuffer != null) {
                String choice = (String) cboJitterBuffer.getSelectedItem();
                try {
                    // Thay muc dem se xoa hang doi cu va tich luy lai theo cau hinh moi.
                    jitterBuffer.setBufferSizeMs(Integer.parseInt(choice.replace("ms", "")));
                    txtLog.append("Jitter Buffer dat thanh " + jitterBuffer.getBufferSizeMs()
                            + " ms; dang tich luy lai truoc khi phat.\n");
                    refreshStats();
                } catch (IllegalArgumentException e) {
                    javax.swing.JOptionPane.showMessageDialog(this,
                            "Kich thuoc Jitter Buffer khong hop le.", "Loi",
                            javax.swing.JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        sliderVolume.addChangeListener(event -> {
            if (audioPlayback != null) {
                audioPlayback.setVolumePercent(sliderVolume.getValue());
            }
        });
        if (jitterBuffer != null) {
            lblJitterBuffer.setText("Jitter Buffer: " + jitterBuffer.getBufferSizeMs() + " ms");
        }
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                // Giai phong socket, luong phat va timer khi dong cua so client.
                stopClient();
                stopDeviceCheckTimer();
            }
        });
        statsTimer = new Timer(500, event -> refreshStats());
        btnPlayPause.setText("Tam dung");
    }

    private void initializeSpeakerComboBox() {
        availableSpeakers = AudioPlayback.getAvailableSpeakers();
        cboOutputDevice.removeAllItems();

        for (Mixer.Info speaker : availableSpeakers) {
            cboOutputDevice.addItem(speaker.getName());
        }

        int defaultIndex = -1;
        for (int i = 0; i < availableSpeakers.size(); i++) {
            String name = availableSpeakers.get(i).getName().toLowerCase();
            if (name.contains("default") || name.contains("primary")) {
                defaultIndex = i;
                break;
            }
        }

        if (defaultIndex >= 0) {
            cboOutputDevice.setSelectedIndex(defaultIndex);
        } else if (!availableSpeakers.isEmpty()) {
            cboOutputDevice.setSelectedIndex(0);
        }

        cboOutputDevice.addActionListener(e -> {
            int selectedIndex = cboOutputDevice.getSelectedIndex();
            if (selectedIndex >= 0 && selectedIndex < availableSpeakers.size()) {
                Mixer.Info selected = availableSpeakers.get(selectedIndex);

                if (audioPlayback != null && audioPlayback.isPlaying()) {
                    try {
                        // Doi thiet bi khi dang nghe can khoi tao lai line phat.
                        audioPlayback.setSpeaker(selected);
                        audioPlayback.restartPlayback();
                        txtLog.append("Da doi sang: " + selected.getName() + "\n");
                    } catch (LineUnavailableException ex) {
                        javax.swing.JOptionPane.showMessageDialog(this,
                            "Khong the doi loa: " + ex.getMessage(),
                            "Loi", javax.swing.JOptionPane.ERROR_MESSAGE);
                    }
                } else {
                    if (audioPlayback != null) {
                        audioPlayback.setSpeaker(selected);
                    }
                }
            }
        });
    }

    private void setupDeviceChangeListener() {
        if (audioPlayback != null) {
            audioPlayback.setDeviceChangeListener(new AudioPlayback.DeviceChangeListener() {
                @Override
                public void onDeviceRemoved(Mixer.Info oldDevice) {
                    txtLog.append("Thiet bi " + (oldDevice != null ? oldDevice.getName() : "default")
                        + " bi rut, dang chuyen sang default\n");
                    refreshSpeakerComboBox();

                    if (audioPlayback.isPlaying()) {
                        try {
                            jitterBuffer.clear(); // Clear jitter buffer truoc khi restart
                            audioPlayback.restartPlayback();
                        } catch (LineUnavailableException ex) {
                            txtLog.append("Loi khi restart: " + ex.getMessage() + "\n");
                        }
                    }
                }

                @Override
                public void onDeviceAdded(List<Mixer.Info> newDevices) {
                    for (Mixer.Info device : newDevices) {
                        txtLog.append("Phat hien thiet bi moi: " + device.getName() + "\n");
                    }
                    refreshSpeakerComboBox();
                }
            });
        }
    }

    private void refreshSpeakerComboBox() {
        Mixer.Info currentSelection = audioPlayback.getSelectedSpeaker();
        int currentIndex = -1;

        if (currentSelection != null) {
            currentIndex = availableSpeakers.indexOf(currentSelection);
        }

        availableSpeakers = AudioPlayback.getAvailableSpeakers();
        String selectedItem = cboOutputDevice.getSelectedItem() != null
            ? cboOutputDevice.getSelectedItem().toString()
            : "";

        cboOutputDevice.removeAllItems();
        for (Mixer.Info speaker : availableSpeakers) {
            cboOutputDevice.addItem(speaker.getName());
        }

        if (currentSelection != null && availableSpeakers.contains(currentSelection)) {
            int newIndex = availableSpeakers.indexOf(currentSelection);
            cboOutputDevice.setSelectedIndex(newIndex);
        } else {
            int defaultIndex = -1;
            for (int i = 0; i < availableSpeakers.size(); i++) {
                String name = availableSpeakers.get(i).getName().toLowerCase();
                if (name.contains("default") || name.contains("primary")) {
                    defaultIndex = i;
                    break;
                }
            }

            if (defaultIndex >= 0) {
                cboOutputDevice.setSelectedIndex(defaultIndex);
            } else if (!availableSpeakers.isEmpty()) {
                cboOutputDevice.setSelectedIndex(0);
            }
        }
    }

    private void startDeviceCheckTimer() {
        // Swing Timer kiem tra thiet bi dinh ky tren Event Dispatch Thread.
        deviceCheckTimer = new Timer(1000, e -> {
            if (audioPlayback != null) {
                audioPlayback.checkForDeviceChanges();

                if (!audioPlayback.isCurrentDeviceAvailable()) {
                    audioPlayback.autoSwitchToDefault();
                }
            }
        });
        deviceCheckTimer.start();
    }

    private void stopDeviceCheckTimer() {
        if (deviceCheckTimer != null) {
            deviceCheckTimer.stop();
            deviceCheckTimer = null;
        }
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jPanel11 = new javax.swing.JPanel();
        jLabel20 = new javax.swing.JLabel();
        jPanel1 = new javax.swing.JPanel();
        jPanel2 = new javax.swing.JPanel();
        jLabel1 = new javax.swing.JLabel();
        jPanel3 = new javax.swing.JPanel();
        jLabel3 = new javax.swing.JLabel();
        jLabel9 = new javax.swing.JLabel();
        cboOutputDevice = new javax.swing.JComboBox<>();
        btnPlayPause = new javax.swing.JButton();
        jPanel4 = new javax.swing.JPanel();
        jLabel5 = new javax.swing.JLabel();
        jLabel6 = new javax.swing.JLabel();
        jLabel7 = new javax.swing.JLabel();
        jPanel6 = new javax.swing.JPanel();
        jLabel13 = new javax.swing.JLabel();
        jLabel23 = new javax.swing.JLabel();
        jLabel31 = new javax.swing.JLabel();
        cboJitterBuffer = new javax.swing.JComboBox<>();
        lblJitterBuffer = new javax.swing.JLabel();
        btnResetStats = new javax.swing.JButton();
        jLabel11 = new javax.swing.JLabel();
        jScrollPane1 = new javax.swing.JScrollPane();
        txtLog = new javax.swing.JTextArea();
        lblPacketsReceived = new javax.swing.JLabel();
        lblPacketsLost = new javax.swing.JLabel();
        lblJitter = new javax.swing.JLabel();
        lblLatency = new javax.swing.JLabel();
        lblBytesReceived = new javax.swing.JLabel();
        lblPacketLossRate = new javax.swing.JLabel();
        jPanel7 = new javax.swing.JPanel();
        jLabel16 = new javax.swing.JLabel();
        sliderVolume = new javax.swing.JSlider();
        btnVolumeDown = new javax.swing.JButton();
        lblVolumeValue = new javax.swing.JLabel();
        jProgressBar2 = new javax.swing.JProgressBar();
        jLabel18 = new javax.swing.JLabel();
        btnVolumeUp = new javax.swing.JButton();

        javax.swing.GroupLayout jPanel11Layout = new javax.swing.GroupLayout(jPanel11);
        jPanel11.setLayout(jPanel11Layout);
        jPanel11Layout.setHorizontalGroup(
            jPanel11Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 160, Short.MAX_VALUE)
        );
        jPanel11Layout.setVerticalGroup(
            jPanel11Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 57, Short.MAX_VALUE)
        );

        jLabel20.setForeground(new java.awt.Color(255, 255, 255));
        jLabel20.setText("Ty le mat goi");

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setResizable(false);

        jPanel1.setBackground(new java.awt.Color(0, 0, 102));

        jPanel2.setBackground(new java.awt.Color(0, 51, 102));
        jPanel2.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 51, 255)));

        jLabel1.setFont(new java.awt.Font("Segoe UI", 1, 36)); // NOI18N
        jLabel1.setForeground(new java.awt.Color(255, 255, 255));
        jLabel1.setText("AUDIO STREAMING");

        javax.swing.GroupLayout jPanel2Layout = new javax.swing.GroupLayout(jPanel2);
        jPanel2.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addGap(370, 370, 370)
                .addComponent(jLabel1)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addGap(21, 21, 21)
                .addComponent(jLabel1)
                .addContainerGap(17, Short.MAX_VALUE))
        );

        jPanel3.setBackground(new java.awt.Color(0, 51, 102));
        jPanel3.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 51, 255)));

        jLabel3.setFont(new java.awt.Font("Segoe UI", 1, 18)); // NOI18N
        jLabel3.setForeground(new java.awt.Color(255, 255, 255));
        jLabel3.setText("DIEU KHIEN PHAT AM THANH");

        jLabel9.setFont(new java.awt.Font("Segoe UI", 1, 18)); // NOI18N
        jLabel9.setForeground(new java.awt.Color(255, 255, 255));
        jLabel9.setText("Thiet bi dau ra");

        cboOutputDevice.setBackground(new java.awt.Color(0, 102, 153));
        cboOutputDevice.setFont(new java.awt.Font("Segoe UI", 1, 12)); // NOI18N
        cboOutputDevice.setForeground(new java.awt.Color(255, 255, 255));
        cboOutputDevice.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Live Streaming (UDP)" }));

        btnPlayPause.setBackground(new java.awt.Color(0, 102, 255));
        btnPlayPause.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        btnPlayPause.setForeground(new java.awt.Color(255, 255, 255));
        btnPlayPause.setText("Play");
        btnPlayPause.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnPlayPauseActionPerformed(evt);
            }
        });

        jPanel4.setBackground(new java.awt.Color(0, 51, 102));
        jPanel4.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 153, 0)));

        jLabel5.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        jLabel5.setForeground(new java.awt.Color(255, 255, 255));
        jLabel5.setText("Da ket noi");

        jLabel6.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        jLabel6.setForeground(new java.awt.Color(255, 255, 255));
        jLabel6.setText("Server:");

        jLabel7.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        jLabel7.setForeground(new java.awt.Color(255, 255, 255));
        jLabel7.setText("Client:");

        javax.swing.GroupLayout jPanel4Layout = new javax.swing.GroupLayout(jPanel4);
        jPanel4.setLayout(jPanel4Layout);
        jPanel4Layout.setHorizontalGroup(
            jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel4Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel5)
                    .addComponent(jLabel6)
                    .addComponent(jLabel7))
                .addContainerGap(182, Short.MAX_VALUE))
        );
        jPanel4Layout.setVerticalGroup(
            jPanel4Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel4Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jLabel5)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jLabel6)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, 10, Short.MAX_VALUE)
                .addComponent(jLabel7)
                .addContainerGap())
        );

        javax.swing.GroupLayout jPanel3Layout = new javax.swing.GroupLayout(jPanel3);
        jPanel3.setLayout(jPanel3Layout);
        jPanel3Layout.setHorizontalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel3Layout.createSequentialGroup()
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addGap(14, 14, 14)
                        .addComponent(jLabel3))
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addGap(93, 93, 93)
                        .addComponent(btnPlayPause, javax.swing.GroupLayout.PREFERRED_SIZE, 113, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addGap(137, 137, 137)
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel9)
                    .addComponent(cboOutputDevice, javax.swing.GroupLayout.PREFERRED_SIZE, 309, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jPanel4, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addGap(18, 18, 18))
        );
        jPanel3Layout.setVerticalGroup(
            jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel3Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jPanel4, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGroup(jPanel3Layout.createSequentialGroup()
                        .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(jLabel3)
                            .addComponent(jLabel9, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                        .addGap(17, 17, 17)
                        .addGroup(jPanel3Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(btnPlayPause, javax.swing.GroupLayout.PREFERRED_SIZE, 39, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(cboOutputDevice, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        jPanel6.setBackground(new java.awt.Color(0, 51, 102));
        jPanel6.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 51, 255)));
        jPanel6.setForeground(new java.awt.Color(0, 51, 255));

        jLabel13.setFont(new java.awt.Font("Segoe UI", 1, 18)); // NOI18N
        jLabel13.setForeground(new java.awt.Color(255, 255, 255));
        jLabel13.setText("Thong ke mang");

        jLabel23.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        jLabel23.setForeground(new java.awt.Color(255, 255, 255));
        jLabel23.setText("Cau hinh Jittter Buffer");

        jLabel31.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        jLabel31.setForeground(new java.awt.Color(255, 255, 255));
        jLabel31.setText("Muc dem");

        cboJitterBuffer.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "50ms", "75ms", "100ms", "200ms", "300ms", "400ms", "500ms" }));
        cboJitterBuffer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cboJitterBufferActionPerformed(evt);
            }
        });

        lblJitterBuffer.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        lblJitterBuffer.setForeground(new java.awt.Color(255, 255, 255));
        lblJitterBuffer.setText("Jitter Buffer: 50 ms");

        btnResetStats.setBackground(new java.awt.Color(0, 51, 255));
        btnResetStats.setForeground(new java.awt.Color(255, 255, 255));
        btnResetStats.setText("Dat lai thong ke");
        btnResetStats.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnResetStatsActionPerformed(evt);
            }
        });

        jLabel11.setFont(new java.awt.Font("Segoe UI", 0, 14)); // NOI18N
        jLabel11.setForeground(new java.awt.Color(255, 255, 255));
        jLabel11.setText("Nhat ky hoat dong");

        txtLog.setEditable(false);
        txtLog.setColumns(20);
        txtLog.setLineWrap(true);
        txtLog.setRows(5);
        txtLog.setWrapStyleWord(true);
        jScrollPane1.setViewportView(txtLog);

        lblPacketsReceived.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblPacketsReceived.setForeground(new java.awt.Color(255, 255, 255));
        lblPacketsReceived.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lblPacketsReceived.setText("Goi nhan: 0");
        lblPacketsReceived.setToolTipText("");
        lblPacketsReceived.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 1, 1, 1, new java.awt.Color(0, 204, 255)));

        lblPacketsLost.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblPacketsLost.setForeground(new java.awt.Color(255, 255, 255));
        lblPacketsLost.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lblPacketsLost.setText("Goi mat: 0");
        lblPacketsLost.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 1, 1, 1, new java.awt.Color(0, 204, 255)));

        lblJitter.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblJitter.setForeground(new java.awt.Color(255, 255, 255));
        lblJitter.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lblJitter.setText("Jitter(ms): --ms");
        lblJitter.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 1, 1, 1, new java.awt.Color(0, 204, 255)));

        lblLatency.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblLatency.setForeground(new java.awt.Color(255, 255, 255));
        lblLatency.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lblLatency.setText("Uoc tinh mang (RTT/2): -- ms");
        lblLatency.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 1, 1, 1, new java.awt.Color(0, 204, 255)));

        lblBytesReceived.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblBytesReceived.setForeground(new java.awt.Color(255, 255, 255));
        lblBytesReceived.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lblBytesReceived.setText("Dung luong: 0kb");
        lblBytesReceived.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 1, 1, 1, new java.awt.Color(0, 204, 255)));

        lblPacketLossRate.setFont(new java.awt.Font("Segoe UI", 1, 14)); // NOI18N
        lblPacketLossRate.setForeground(new java.awt.Color(255, 255, 255));
        lblPacketLossRate.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        lblPacketLossRate.setText("Ty le mat goi: 0.00%");
        lblPacketLossRate.setBorder(javax.swing.BorderFactory.createMatteBorder(1, 1, 1, 1, new java.awt.Color(0, 204, 255)));

        javax.swing.GroupLayout jPanel6Layout = new javax.swing.GroupLayout(jPanel6);
        jPanel6.setLayout(jPanel6Layout);
        jPanel6Layout.setHorizontalGroup(
            jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel6Layout.createSequentialGroup()
                .addGap(18, 18, 18)
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING, false)
                    .addGroup(jPanel6Layout.createSequentialGroup()
                        .addComponent(jLabel11)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addComponent(btnResetStats))
                    .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                        .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 589, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addComponent(jLabel23)
                        .addGroup(jPanel6Layout.createSequentialGroup()
                            .addComponent(jLabel31)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                            .addComponent(cboJitterBuffer, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                            .addComponent(lblJitterBuffer))
                        .addComponent(jLabel13)))
                .addGap(40, 40, 40)
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(lblPacketsReceived, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblPacketsLost, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblJitter, javax.swing.GroupLayout.DEFAULT_SIZE, 215, Short.MAX_VALUE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(lblLatency, javax.swing.GroupLayout.DEFAULT_SIZE, 215, Short.MAX_VALUE)
                    .addComponent(lblBytesReceived, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(lblPacketLossRate, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        jPanel6Layout.setVerticalGroup(
            jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel6Layout.createSequentialGroup()
                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addGroup(jPanel6Layout.createSequentialGroup()
                        .addGap(135, 135, 135)
                        .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(lblPacketsLost, javax.swing.GroupLayout.PREFERRED_SIZE, 70, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(lblBytesReceived, javax.swing.GroupLayout.PREFERRED_SIZE, 70, javax.swing.GroupLayout.PREFERRED_SIZE))
                        .addGap(34, 34, 34)
                        .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblLatency, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(lblJitter, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
                    .addGroup(jPanel6Layout.createSequentialGroup()
                        .addComponent(jLabel13)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                        .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel6Layout.createSequentialGroup()
                                .addComponent(jLabel23)
                                .addGap(18, 18, 18)
                                .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                    .addComponent(jLabel31)
                                    .addComponent(cboJitterBuffer, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addComponent(lblJitterBuffer)))
                            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                                .addComponent(lblPacketsReceived, javax.swing.GroupLayout.PREFERRED_SIZE, 70, javax.swing.GroupLayout.PREFERRED_SIZE)
                                .addComponent(lblPacketLossRate, javax.swing.GroupLayout.PREFERRED_SIZE, 70, javax.swing.GroupLayout.PREFERRED_SIZE)))
                        .addGap(0, 0, Short.MAX_VALUE)
                        .addGroup(jPanel6Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(btnResetStats, javax.swing.GroupLayout.PREFERRED_SIZE, 36, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(jLabel11))
                        .addGap(18, 18, 18)
                        .addComponent(jScrollPane1, javax.swing.GroupLayout.PREFERRED_SIZE, 157, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addGap(94, 94, 94))
        );

        jPanel7.setBackground(new java.awt.Color(0, 51, 102));
        jPanel7.setBorder(javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0, 51, 255)));
        jPanel7.setForeground(new java.awt.Color(0, 51, 102));

        jLabel16.setFont(new java.awt.Font("Segoe UI", 1, 18)); // NOI18N
        jLabel16.setForeground(new java.awt.Color(255, 255, 255));
        jLabel16.setText("Am luong");

        sliderVolume.setFont(new java.awt.Font("Segoe UI", 1, 18)); // NOI18N
        sliderVolume.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                sliderVolumeStateChanged(evt);
            }
        });

        btnVolumeDown.setBackground(new java.awt.Color(0, 51, 102));
        btnVolumeDown.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnVolumeDown.setForeground(new java.awt.Color(255, 255, 255));
        btnVolumeDown.setText("-");
        btnVolumeDown.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnVolumeDownActionPerformed(evt);
            }
        });

        lblVolumeValue.setFont(new java.awt.Font("Segoe UI", 1, 16)); // NOI18N
        lblVolumeValue.setForeground(new java.awt.Color(255, 255, 255));
        lblVolumeValue.setText("50%");

        jLabel18.setFont(new java.awt.Font("Segoe UI", 1, 18)); // NOI18N
        jLabel18.setForeground(new java.awt.Color(255, 255, 255));
        jLabel18.setText("Cuong do am thanh (Input level)");

        btnVolumeUp.setBackground(new java.awt.Color(0, 51, 102));
        btnVolumeUp.setFont(new java.awt.Font("Segoe UI", 1, 13)); // NOI18N
        btnVolumeUp.setForeground(new java.awt.Color(255, 255, 255));
        btnVolumeUp.setText("+");
        btnVolumeUp.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnVolumeUpActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout jPanel7Layout = new javax.swing.GroupLayout(jPanel7);
        jPanel7.setLayout(jPanel7Layout);
        jPanel7Layout.setHorizontalGroup(
            jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel7Layout.createSequentialGroup()
                .addGap(15, 15, 15)
                .addGroup(jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel7Layout.createSequentialGroup()
                        .addComponent(btnVolumeDown, javax.swing.GroupLayout.PREFERRED_SIZE, 50, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(sliderVolume, javax.swing.GroupLayout.PREFERRED_SIZE, 413, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(btnVolumeUp, javax.swing.GroupLayout.PREFERRED_SIZE, 50, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(lblVolumeValue, javax.swing.GroupLayout.PREFERRED_SIZE, 50, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addComponent(jLabel16))
                .addGap(44, 44, 44)
                .addGroup(jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jProgressBar2, javax.swing.GroupLayout.PREFERRED_SIZE, 342, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel18))
                .addContainerGap(118, Short.MAX_VALUE))
        );
        jPanel7Layout.setVerticalGroup(
            jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel7Layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jLabel18)
                    .addComponent(jLabel16, javax.swing.GroupLayout.Alignment.TRAILING))
                .addGroup(jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel7Layout.createSequentialGroup()
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addGroup(jPanel7Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(lblVolumeValue)
                            .addComponent(jProgressBar2, javax.swing.GroupLayout.PREFERRED_SIZE, 34, javax.swing.GroupLayout.PREFERRED_SIZE)
                            .addComponent(btnVolumeUp, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 36, javax.swing.GroupLayout.PREFERRED_SIZE)))
                    .addGroup(jPanel7Layout.createSequentialGroup()
                        .addGap(18, 18, 18)
                        .addComponent(sliderVolume, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(jPanel7Layout.createSequentialGroup()
                        .addGap(10, 10, 10)
                        .addComponent(btnVolumeDown, javax.swing.GroupLayout.PREFERRED_SIZE, 38, javax.swing.GroupLayout.PREFERRED_SIZE)))
                .addContainerGap(29, Short.MAX_VALUE))
        );

        javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jPanel2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(jPanel3, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addGroup(jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                        .addContainerGap()
                        .addComponent(jPanel6, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addComponent(jPanel7, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addComponent(jPanel2, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel3, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel7, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addComponent(jPanel6, javax.swing.GroupLayout.PREFERRED_SIZE, 335, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap())
        );

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jPanel1, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jPanel1, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void btnVolumeUpActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnVolumeUpActionPerformed
        // TODO add your handling code here:
        sliderVolume.setValue(
        Math.min(100, sliderVolume.getValue() + 5)
    );
    }//GEN-LAST:event_btnVolumeUpActionPerformed

    private void btnVolumeDownActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnVolumeDownActionPerformed
        // TODO add your handling code here:
        sliderVolume.setValue(
        Math.max(0, sliderVolume.getValue() - 5)
    );
    }//GEN-LAST:event_btnVolumeDownActionPerformed

    private void btnResetStatsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnResetStatsActionPerformed
        if (audioClient != null) {
            audioClient.resetStats();
        }
        lblPacketsReceived.setText("Goi nhan: 0");
        lblPacketsLost.setText("Goi mat: 0");
        lblPacketLossRate.setText("Ty le mat goi: 0.00%");
        lblLatency.setText("Mang (RTT/2): -- ms");
        lblJitter.setText("Jitter: -- ms");
        lblBytesReceived.setText("Dung luong nhan: 0 KB");
        txtLog.append("Da yeu cau dat lai thong ke.\n");
    }//GEN-LAST:event_btnResetStatsActionPerformed

    private void btnPlayPauseActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnPlayPauseActionPerformed
    if (audioPlayback == null) {
        return;
    }
    try {
        if (!isPlaying) {
            audioPlayback.resumePlayback();
            isPlaying = true;
            btnPlayPause.setText("Tam dung");
        } else {
            audioPlayback.pausePlayback();
            isPlaying = false;
            btnPlayPause.setText("Phat");
        }
    } catch (LineUnavailableException e) {
        showAudioError(e);
    }
    }//GEN-LAST:event_btnPlayPauseActionPerformed

    private void sliderVolumeStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_sliderVolumeStateChanged
        // TODO add your handling code here:
        int volume = sliderVolume.getValue();
        lblVolumeValue.setText(volume + "%");
    }//GEN-LAST:event_sliderVolumeStateChanged

    private void cboJitterBufferActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cboJitterBufferActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_cboJitterBufferActionPerformed

    private void refreshStats() {
        if (audioClient == null) {
            return;
        }
        int outputLevel = audioPlayback == null ? 0 : audioPlayback.getOutputLevelPercent();
        jProgressBar2.setValue(outputLevel);
        jProgressBar2.setString("Am thanh dang phat: " + outputLevel + "%");
        if (audioClient.isConnectionAccepted() && audioClient.isServerReachable()) {
            long authenticationFailures = audioClient.getAuthenticationFailures();
            jLabel5.setText(authenticationFailures == 0 ? "Da ket noi"
                    : "Da ket noi - goi sai khoa: " + authenticationFailures);
        } else if (audioClient.isConnectionAccepted()) {
            jLabel5.setText("Mat ket noi - dang cho phan hoi");
        } else {
            jLabel5.setText(audioClient.getConnectionMessage());
        }
        jLabel5.setToolTipText(audioClient.getConnectionMessage());
        lblPacketsReceived.setText("Goi nhan: " + audioClient.getPacketsReceived());
        lblPacketsLost.setText("Goi mat: " + audioClient.getPacketsLost());
        lblPacketLossRate.setText(String.format("Ty le mat goi: %.2f%%",
                audioClient.getPacketLossRate()));
        double oneWayEstimate = audioClient.getEstimatedOneWayLatencyMs();
        if (Double.isNaN(oneWayEstimate)) {
            lblLatency.setText("Mang (RTT/2): -- ms");
            lblJitter.setText(String.format("Jitter: %.2f ms", audioClient.getCurrentJitter()));
        } else {
            // Day la uoc tinh RTT/2; cong frame va buffer chi de tham khao, khong phai do end-to-end.
            double effectiveBufferMs = jitterBuffer == null
                    ? 0 : jitterBuffer.getEffectiveBufferSizeMs();
            double bufferedEstimate = oneWayEstimate + AudioConstants.FRAME_MS + effectiveBufferMs;
            lblLatency.setText(String.format("Mang (RTT/2): %.1f ms", oneWayEstimate));
            lblLatency.setToolTipText(
                    "Uoc tinh mang tu mot nua RTT dieu khien; khong phai latency end-to-end.");
            lblJitter.setText(String.format("Jitter %.2f ms",
                    audioClient.getCurrentJitter(), bufferedEstimate,
                    bufferedEstimate >= 150 ? " (tren muc tieu 150 ms)" : ""));
        }
        lblBytesReceived.setText(String.format("Dung luong nhan: %d KB",
                audioClient.getKilobytesReceived()));
        if (jitterBuffer != null) {
            lblJitterBuffer.setText("Buffer: " + jitterBuffer.getBufferSizeMs() + "->"
                    + jitterBuffer.getEffectiveBufferSizeMs() + " ms - "
                    + jitterBuffer.size() + " goi"
                    + (jitterBuffer.isPlayingOut() ? " - dang phat" : " - dang tich luy"));
            lblJitterBuffer.setToolTipText(
                    "Khi doi muc, buffer xoa goi cu va tich luy theo muc moi truoc khi phat lai.");
        }
    }

    private void stopClient() {
        if (statsTimer != null) {
            statsTimer.stop();
        }
        if (deviceCheckTimer != null) {
            deviceCheckTimer.stop();
        }
        if (audioClient != null) {
            audioClient.stopClient();
        }
    }

    private void showAudioError(Exception error) {
        javax.swing.JOptionPane.showMessageDialog(this, error.getMessage(),
                "Khong the phat am thanh", javax.swing.JOptionPane.ERROR_MESSAGE);
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(ListenForm.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(ListenForm.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(ListenForm.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(ListenForm.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new ListenForm().setVisible(true);
            }
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnPlayPause;
    private javax.swing.JButton btnResetStats;
    private javax.swing.JButton btnVolumeDown;
    private javax.swing.JButton btnVolumeUp;
    private javax.swing.JComboBox<String> cboJitterBuffer;
    private javax.swing.JComboBox<String> cboOutputDevice;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel11;
    private javax.swing.JLabel jLabel13;
    private javax.swing.JLabel jLabel16;
    private javax.swing.JLabel jLabel18;
    private javax.swing.JLabel jLabel20;
    private javax.swing.JLabel jLabel23;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel31;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JLabel jLabel6;
    private javax.swing.JLabel jLabel7;
    private javax.swing.JLabel jLabel9;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel11;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JPanel jPanel3;
    private javax.swing.JPanel jPanel4;
    private javax.swing.JPanel jPanel6;
    private javax.swing.JPanel jPanel7;
    private javax.swing.JProgressBar jProgressBar2;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JLabel lblBytesReceived;
    private javax.swing.JLabel lblJitter;
    private javax.swing.JLabel lblJitterBuffer;
    private javax.swing.JLabel lblLatency;
    private javax.swing.JLabel lblPacketLossRate;
    private javax.swing.JLabel lblPacketsLost;
    private javax.swing.JLabel lblPacketsReceived;
    private javax.swing.JLabel lblVolumeValue;
    private javax.swing.JSlider sliderVolume;
    private javax.swing.JTextArea txtLog;
    // End of variables declaration//GEN-END:variables
}
