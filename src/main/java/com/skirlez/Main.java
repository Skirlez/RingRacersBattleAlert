package com.skirlez;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.*;
import java.awt.*;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class Main {

	private static class ServerCheckerRunnable implements Runnable {

		private int minimumPlayers;
		private int maximumTicDelay;
		public ServerCheckerRunnable(int minimumPlayers, int maximumTicDelay) {
			this.minimumPlayers = minimumPlayers;
			this.maximumTicDelay = maximumTicDelay;
		}

		private volatile boolean shouldStop = false;

		public volatile List<JSONObject> result = new ArrayList<JSONObject>();

		private List<JSONObject> potentialServers = new ArrayList<JSONObject>();
		private List<JSONObject> buildingResult = new ArrayList<JSONObject>();

		private Thread pingerThread = null;
		private ServerPinger pingerRunnable = null;

		// check every this amount of miliseconds
		private long checkFrequency = 1000 * 60;
		private long lastChecked = System.currentTimeMillis() - checkFrequency;


		@Override
		public void run() {
			while (!shouldStop && result.size() == 0) {
				if (potentialServers.size() == 0) {
					if (Math.abs(System.currentTimeMillis() - lastChecked) < checkFrequency)
						continue;
					System.out.println("Checking the servers!");

					Optional<JSONObject> maybeJson = getServersJson();
					if (maybeJson.isEmpty())
						continue;
					JSONObject json = maybeJson.get();
					JSONArray serversArray = json.getJSONArray("servers");
					List<JSONObject> servers = new ArrayList<JSONObject>();
					List<JSONObject> goodServers = new ArrayList<JSONObject>();
					serversArray.forEach((object) -> servers.add((JSONObject) object));
					try {
						 potentialServers = servers.stream()
							.filter((server) -> {
									if (server.has("error")
											|| !server.has("joinable_state")
											|| !server.has("gametype") || !server.has("players"))
										return false;
									return (server.getString("joinable_state").equals("joinable")
										&& server.getString("gametype").equals("Battle")
										&& server.getJSONArray("players").length() >= minimumPlayers);
								}
							).collect(Collectors.toCollection(ArrayList::new));
					}
					catch (JSONException e) {
						e.printStackTrace();
					}
					if (potentialServers.size() > 0) {
						System.out.println("Found " + potentialServers.size() + " potentially good servers. Let's ping them!");
					}
					lastChecked = System.currentTimeMillis();
				}
				else {
					if (pingerThread == null) {
						JSONObject server = potentialServers.get(0);
						JSONArray address = server.getJSONArray("address");
						String ip = address.getString(0);
						int port = address.getInt(1);

						System.out.println("Pinging " + sanitizeServerName(server.getString("server_name")) + ", " + ip + ":" + port + "...");
						pingerRunnable = new ServerPinger(ip, port);
						pingerThread = new Thread(pingerRunnable);
						pingerThread.start();
					}
					else if (!pingerThread.isAlive()) {
						long time = pingerRunnable.result;
						if (time == 0) {
							System.out.println("Server did not respond...");
						}
						else {
							// there are 35 tics in a second
							float uniform = ((float) time) / 1000f;
							int tics = Math.round(uniform * 35f);
							System.out.println("Server responded in " + time + " ms, or " + tics + " tics.");
							if (tics <= maximumTicDelay)
								buildingResult.add(potentialServers.get(0));
						}
						potentialServers.remove(0);
						pingerThread = null;
						pingerRunnable = null;
					}
					if (potentialServers.size() == 0)
						result = buildingResult;
				}
			}
		}
	}

	private static class ServerPinger implements Runnable {
		public volatile long result = 0;

		private String ip;
		private int port;
		public ServerPinger(String ip, int port) {
			this.ip = ip;
			this.port = port;
		}

		@Override
		public void run() {
			// I do not know what this mystical sequence of bytes represents.
			// It is something the Ring Racers client sends to servers when looking at the server browser (obtained through wireshark)
			// The servers don't respond to an empty packet. They probably send something back. We don't really care.
			byte[] data = new byte[] {  (byte)253, 75, 35, 1, 0, 0, 12, 31, 2, 21, (byte)179, 16, 1};
			InetAddress inetAddress;
			try {
				inetAddress = InetAddress.getByName(ip);
			} catch (UnknownHostException e) {
				return;
			}
			try {
				DatagramSocket socket = new DatagramSocket();
				socket.setSoTimeout(1000);
				DatagramPacket packet = new DatagramPacket(data, data.length, inetAddress, port);
				long start = System.currentTimeMillis();
				socket.send(packet);
				try {
					byte[] recv = new byte[1024];
					DatagramPacket recvPacket = new DatagramPacket(recv, recv.length);
					socket.receive(recvPacket);
					long end = System.currentTimeMillis();
					socket.close();

					result = end - start;
				} catch (Exception e) {
					return;
				}
			} catch (Exception e) {
				e.printStackTrace();
				return;
			}

		}
	}

	private static Thread currentThread = null;
	private static ServerCheckerRunnable currentThreadRunnable = null;
	private static Clip clip = null;

	public static void main(String[] args) {
		try {
			AudioInputStream audioStream = AudioSystem.getAudioInputStream(Main.class.getResource("/alarm.wav"));
			clip = AudioSystem.getClip();
			clip.open(audioStream);
		}
		catch (Exception ignored) { }


		JFrame frame = new JFrame("Ring Racers Battle Alert");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setSize(700, 400);
		Font font = new Font("Arial", Font.PLAIN, 24);

		Image image = Toolkit.getDefaultToolkit().getImage(Main.class.getResource("/icon.png"));
		frame.setIconImage(image);

		JPanel leftPanel = new JPanel();
		leftPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
		JPanel rightPanel = new JPanel();
		rightPanel.setLayout(new FlowLayout(FlowLayout.RIGHT));

		frame.add(leftPanel, BorderLayout.NORTH);
		frame.add(rightPanel, BorderLayout.SOUTH);

		JPanel optionsPanel = new JPanel();
		optionsPanel.setLayout(new GridBagLayout());

		GridBagConstraints gameBoyColor = new GridBagConstraints();
		gameBoyColor.gridx = 0;
		gameBoyColor.gridy = 0;
		gameBoyColor.anchor = GridBagConstraints.LINE_START;

		JPanel playerCountPanel = new JPanel();
		JTextField playerCountField = new JTextField(2);
		playerCountField.setText("3");
		{
			playerCountPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
			playerCountField.setFont(font);
			playerCountPanel.add(playerCountField);
			JLabel label = new JLabel("Minimum player count");
			label.setFont(font);
			playerCountPanel.add(label);
		}
		JPanel ticPanel = new JPanel();
		JTextField ticField = new JTextField(2);
		ticField.setText("4");
		{
			ticPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
			ticField.setFont(font);
			ticPanel.add(ticField);
			JLabel label = new JLabel("Maximum acceptable Tic delay");
			label.setFont(font);
			ticPanel.add(label);
		}
		JPanel soundPanel = new JPanel();
		JCheckBox soundCheckBox = new JCheckBox();
		{
			soundPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
			ImageIcon checkedBox = new ImageIcon(Main.class.getResource("/checked_box.png"));
			ImageIcon uncheckedBox = new ImageIcon(Main.class.getResource("/unchecked_box.png"));

			soundCheckBox.setIcon(uncheckedBox);
			soundCheckBox.setSelectedIcon(checkedBox);
			soundPanel.add(soundCheckBox);
			JLabel label = new JLabel("Play VERY loud alarm together with the pop-up");
			label.setFont(font);
			soundPanel.add(label);
		}

		optionsPanel.add(playerCountPanel, gameBoyColor);
		gameBoyColor.gridy++;
		optionsPanel.add(ticPanel, gameBoyColor);
		gameBoyColor.gridy++;
		optionsPanel.add(soundPanel, gameBoyColor);
		gameBoyColor.gridy++;


		leftPanel.add(optionsPanel);



		JButton button = new JButton("GO!");
		button.setPreferredSize(new Dimension(200, 60));
		final Font bigFont = new Font("Arial", Font.BOLD, 30);
		button.setFont(bigFont);
		rightPanel.add(button);

		button.addActionListener((ActionEvent event) ->  {

			if (currentThreadRunnable == null) {
				System.out.println("Starting listen thread");
				int minimumPlayers = 3;
				int maximumTicDelay = 4;
				try {
					minimumPlayers = Integer.parseInt(playerCountField.getText());
					maximumTicDelay = Integer.parseInt(ticField.getText());
				}
				catch (NumberFormatException ignored) {
					return;
				}
				currentThreadRunnable = new ServerCheckerRunnable(minimumPlayers, maximumTicDelay);
				currentThread = new Thread(currentThreadRunnable);
				currentThread.start();
				button.setText("Checking...");
				button.setFont(font);
			}
			else {
				if (!currentThread.isAlive()) {
					// died without us, meaning result is ready to collect, so just leave
					return;
				}
				currentThreadRunnable.shouldStop = true;
				try {
					currentThread.join();
				} catch (InterruptedException ignored) {
				}
				System.out.println("Listen thread is gone");
				currentThreadRunnable = null;
				currentThread = null;
				button.setFont(bigFont);
				button.setText("GO!");
			}
		});

		Timer timer = new Timer(200, new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {

				if (currentThread == null)
					return;
				if (!currentThread.isAlive()) {
					List<JSONObject> result = currentThreadRunnable.result;
					String names = result.stream()
						.map(server -> sanitizeServerName(server.getString("server_name")))
						.collect(Collectors.joining(", "));

					boolean playSound = soundCheckBox.isSelected() && clip != null;
					if (playSound)
						clip.loop(Clip.LOOP_CONTINUOUSLY);
					String noun = result.size() == 1 ? "server" : "servers";
					JOptionPane.showMessageDialog(frame, result.size() + " " + noun + " found: " + names, "FOUND BATTLE SERVERS!!!", JOptionPane.INFORMATION_MESSAGE);
					if (playSound)
						clip.stop();
					button.setFont(bigFont);
					button.setText("GO!");

					currentThread = null;
					currentThreadRunnable = null;
				}
			}
		});
		timer.start();
		frame.setVisible(true);
	}
	private static final String jsonLink = "https://ms.kartkrew.org/list.json";
	private static Optional<JSONObject> getServersJson() {
		try {
			URL url = new URL(jsonLink);
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
				return Optional.empty();
			BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
			String jsonString = reader.lines().collect(Collectors.joining());
			reader.close();
			return Optional.of(new JSONObject(jsonString));
		} catch (Exception ignored) {
			return Optional.empty();
		}
	}

	private static String sanitizeServerName(String name) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			if (name.charAt(i) == '^') {
				i++;
				continue;
			}
			builder.append(name.charAt(i));
		}
		return builder.toString();
	}

}
