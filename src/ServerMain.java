import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ServerMain {
    private static final int TCP_PORT = 5555;
    private static final int UDP_PORT = 5556;
    private static final int TOTAL_QUESTIONS = 5;
    private static ConcurrentHashMap<Integer, ClientThread> activeClients = new ConcurrentHashMap<>();
    private static QuestionManager questionManager;
    private static ScheduledExecutorService timerService = Executors.newScheduledThreadPool(1);
    private static volatile String currentQuestionBuzz = null;
    private static volatile boolean questionActive = false;

    public static void main(String[] args) {
        try {
            questionManager = new QuestionManager("questions.txt");

            UDPThread udpThread = new UDPThread(UDP_PORT);
            new Thread(udpThread).start();

            ServerSocket serverSocket = new ServerSocket(TCP_PORT);
            System.out.println("Server started on TCP port " + TCP_PORT);

            new Thread(() -> runGameLoop(udpThread)).start();

            while (true) {
                Socket clientSocket = serverSocket.accept();
                int clientId = generateClientId();
                System.out.println("New client connected. ID: " + clientId);

                ClientThread clientThread = new ClientThread(clientSocket, clientId, udpThread);
                activeClients.put(clientId, clientThread);
                new Thread(clientThread).start();
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    private static void runGameLoop(UDPThread udpThread) {
        try {
            while (questionManager.hasMoreQuestions()) {
                QuestionManager.Question question = questionManager.getNextQuestion();
                currentQuestionBuzz = null;
                questionActive = true;

                sendQuestionToAll(question);

                timerService.schedule(() -> endBuzzPeriod(udpThread),
                        GameProtocol.BUZZ_TIMEOUT, TimeUnit.SECONDS);

                Thread.sleep(GameProtocol.BUZZ_TIMEOUT * 1000);

                if (currentQuestionBuzz == null) {
                    sendToAll(GameProtocol.NEXT);
                    continue;
                }

                Thread.sleep(GameProtocol.ANSWER_TIMEOUT * 1000);

                if (questionActive) {
                    int clientId = Integer.parseInt(currentQuestionBuzz.split("\\|")[1]);
                    ClientThread client = activeClients.get(clientId);
                    if (client != null) {
                        client.updateScore(-20);
                        sendToAll(GameProtocol.SCORE + "|" + clientId + "|" + client.getScore());
                    }
                    sendToAll(GameProtocol.NEXT);
                }
            }

            announceWinner();
            sendToAll(GameProtocol.TERMINATE);
            timerService.shutdown();
        } catch (InterruptedException e) {
            System.err.println("Game loop interrupted: " + e.getMessage());
        }
    }

    private static void sendQuestionToAll(QuestionManager.Question question) {
        String message = GameProtocol.QUESTION + "|" + questionManager.getCurrentQuestionNumber() + "|" +
                question.getQuestionText() + "|" + String.join("|", question.getOptions());
        sendToAll(message);
    }

    private static void endBuzzPeriod(UDPThread udpThread) {
        String buzzMessage = udpThread.getNextMessage();
        if (buzzMessage != null && buzzMessage.startsWith(GameProtocol.BUZZ)) {
            currentQuestionBuzz = buzzMessage;
            String[] parts = buzzMessage.split("\\|");
            int clientId = Integer.parseInt(parts[1]);
            int questionNum = Integer.parseInt(parts[2]);

            if (questionNum == questionManager.getCurrentQuestionNumber()) {
                ClientThread client = activeClients.get(clientId);
                if (client != null) {
                    client.sendMessage(GameProtocol.ACK + "|" + questionNum);
                    while ((buzzMessage = udpThread.getNextMessage()) != null) {
                        String[] lateParts = buzzMessage.split("\\|");
                        int lateClientId = Integer.parseInt(lateParts[1]);
                        ClientThread lateClient = activeClients.get(lateClientId);
                        if (lateClient != null) {
                            lateClient.sendMessage(GameProtocol.NACK + "|" + questionNum);
                        }
                    }
                }
            }
        }
    }

    public static void processAnswer(int clientId, int questionNum, int answer) {
        if (questionNum == questionManager.getCurrentQuestionNumber() && questionActive) {
            questionActive = false;
            ClientThread client = activeClients.get(clientId);
            if (client != null) {
                QuestionManager.Question question = questionManager.getQuestions().get(questionNum - 1);
                if (answer == question.getCorrectAnswer()) {
                    client.updateScore(10);
                    client.sendMessage(GameProtocol.CORRECT + "|10");
                } else {
                    client.updateScore(-10);
                    client.sendMessage(GameProtocol.WRONG + "|-10");
                }
                sendToAll(GameProtocol.SCORE + "|" + clientId + "|" + client.getScore());
            }
            sendToAll(GameProtocol.NEXT);
        }
    }

    private static void sendToAll(String message) {
        activeClients.forEach((id, client) -> client.sendMessage(message));
    }

    private static void announceWinner() {
        ClientThread winner = null;
        int maxScore = Integer.MIN_VALUE;

        for (ClientThread client : activeClients.values()) {
            if (client.getScore() > maxScore) {
                maxScore = client.getScore();
                winner = client;
            }
        }

        if (winner != null) {
            System.out.println("Game over! Winner: Client " + winner.getClientId() +
                    " with score: " + winner.getScore());
        }
    }

    private static int generateClientId() {
        return (int) (Math.random() * 1000000);
    }

    public static void removeClient(int clientId) {
        activeClients.remove(clientId);
        System.out.println("Client " + clientId + " disconnected");
    }
}