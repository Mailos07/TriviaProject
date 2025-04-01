import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class ClientThread implements Runnable {
    private final Socket clientSocket;
    private final int clientId;
    private final UDPThread udpThread;
    private PrintWriter out;
    private BufferedReader in;
    private boolean running = true;
    private int score = 0;

    public ClientThread(Socket socket, int clientId, UDPThread udpThread) {
        this.clientSocket = socket;
        this.clientId = clientId;
        this.udpThread = udpThread;
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

            sendMessage(GameProtocol.WELCOME + "|" + clientId);

            while (running) {
                String input = in.readLine();
                if (input == null) {
                    break;
                }

                if (input.startsWith(GameProtocol.DISCONNECT)) {
                    running = false;
                } else if (input.startsWith("ANSWER|")) {
                    String[] parts = input.split("\\|");
                    int questionNum = Integer.parseInt(parts[1]);
                    int answer = Integer.parseInt(parts[2]);
                    ServerMain.processAnswer(clientId, questionNum, answer);
                }
            }
        } catch (IOException e) {
            System.err.println("ClientThread " + clientId + " error: " + e.getMessage());
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (clientSocket != null) clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing resources: " + e.getMessage());
            }
            ServerMain.removeClient(clientId);
        }
    }

    public void sendMessage(String message) {
        out.println(message);
    }

    public int getClientId() {
        return clientId;
    }

    public int getScore() {
        return score;
    }

    public void updateScore(int delta) {
        score += delta;
    }
}