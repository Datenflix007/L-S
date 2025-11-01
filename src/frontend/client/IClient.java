package frontend.client;

import java.util.List;

/**
 * Contract for client-side UI integrations. Implementations wrap the console client
 * so a GUI can drive discovery, authentication, and the question workflow.
 */
public interface IClient {

    void start();

    void shutdown();

    List<DiscoveredServer> getDiscoveredServers();

    boolean chooseServerByIndex(int index);

    void provideAuthToken(String token);

    boolean retryConnect();

    void requestNextTask();

    void chooseAnswerIndex(int index);

    void openOverview();

    void overviewSelect(int index);

    void confirmSubmission();

    void sendForbiddenKeyWarning(String reason);

    void registerListener(Listener listener);

    interface Listener {
        void onWaitingRoom(String testName, int taskCount);

        void onTask(TaskView task);

        void onOverview(List<OverviewItem> overviewItems);

        void onTestEnded();

        void onError(String message);
    }

    record DiscoveredServer(String ip, int port, String name, String description, String activeTest) {}

    record TaskView(String taskId, String question, List<String> answers, int taskNumber, boolean preview, String taskType) {}

    record OverviewItem(String taskId, String question, String selectedAnswer) {}
}
