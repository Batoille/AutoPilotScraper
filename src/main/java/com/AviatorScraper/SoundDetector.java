package com.AviatorScraper;

import javax.sound.sampled.*;

public class SoundDetector {
    private SoundDetectionListener listener;

    public SoundDetector(SoundDetectionListener listener) {
        this.listener = listener;
    }

    public void detectSound() {
        AudioFormat format = new AudioFormat(8000, 8, 1, true, true);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        try {
            Mixer selectedMixer = null;

            // Loop through available mixers and select "Line 1" (Virtual Audio Cable)
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (mixerInfo.getName().contains("Line 1")) { // Match "Line 1" explicitly
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    if (mixer.isLineSupported(info)) {
                        selectedMixer = mixer;
                        System.out.println("Monitoring input device: " + mixerInfo.getName());
                        break;
                    }
                }
            }

            if (selectedMixer == null) {
                System.err.println("No suitable input device found.");
                return;
            }

            TargetDataLine line = (TargetDataLine) selectedMixer.getLine(info);
            line.open(format);
            line.start();
            System.out.println("Listening for sound...");

            byte[] buffer = new byte[1024];
            int threshold = 10;  // Adjust this threshold as needed
            boolean soundCurrentlyDetected = false;

            while (true) {
                int bytesRead = line.read(buffer, 0, buffer.length);
                boolean soundDetected = false;

                // Check if any audio sample in the buffer exceeds the threshold
                for (int i = 0; i < bytesRead; i++) {
                    if (Math.abs(buffer[i]) > threshold) {
                        soundDetected = true;
                        break;
                    }
                }

                // Trigger sound detection event if sound is detected
                if (soundDetected && !soundCurrentlyDetected) {
                    System.out.println("Sound detected!");
                    soundCurrentlyDetected = true;
                    if (listener != null) {
                        listener.onSoundDetected();
                    }
                } else if (!soundDetected && soundCurrentlyDetected) {
                    soundCurrentlyDetected = false;
                }

                Thread.sleep(100);  // Check sound every 100 ms
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
