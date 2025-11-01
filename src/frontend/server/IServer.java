package frontend.server;

import java.io.File;
import java.util.List;

/**
 * Abstraction used by the server UI so Swing components do not depend on the backend implementation details.
 */
public interface IServer {

    enum DashboardState {
        ROOT,
        TEST_SELECTED,
        HOSTING,
        RUNNING
    }

    void start();

    void shutdown();

    String getBoundIp();

    String getSelectedTestName();

    String getSelectedClassName();

    DashboardState getDashboardState();

    List<TestSummary> listTests();

    List<ClassSummary> listClasses();

    ImportResult importTestFromFile(File file);

    void selectTestById(int id);

    String prepareHostingForClass(int classId, int durationMinutes);

    void resetToRoot();

    int getSelectedTaskCount();

    void abortHosting();

    void startTestRun();

    void pauseTestRun();

    void resumeTestRun();

    void addExtraTimeMinutes(int minutes);

    TimerStatus getTimerStatus();

    List<ClientSummary> getClientSummaries();

    List<Warning> getActiveWarnings();

    void dismissWarning(String warningId);

    int getTaskCountForTest(int testId);

    void endRunningTest();

    record TimerStatus(long totalSeconds, long remainingSeconds, boolean running, boolean paused) {}

    record TestSummary(int id, String name) {}

    record ClassSummary(int id, String name) {}

    record ClientSummary(
            String clientId,
            String studentName,
            String remoteIp,
            String status,
            String token,
            int currentTaskNumber,
            int totalTasks,
            boolean handRaised
    ) {}

    record Warning(
            String id,
            String clientId,
            String studentName,
            String message,
            String timestamp
    ) {}

    record ImportResult(boolean success, String message) {}
}
