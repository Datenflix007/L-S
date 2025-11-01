package frontend.server;

import backend.server.Server;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Bridges the Swing dashboard to the backend server implementation.
 */
final class BackendServerAdapter implements IServer {

    private final Server delegate;

    BackendServerAdapter() {
        this(new Server(false, false));
    }

    BackendServerAdapter(Server delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public String getBoundIp() {
        return delegate.getBoundIp();
    }

    @Override
    public String getSelectedTestName() {
        return delegate.getSelectedTestName();
    }

    @Override
    public String getSelectedClassName() {
        return delegate.getSelectedClassName();
    }

    @Override
    public DashboardState getDashboardState() {
        Server.DashboardState state = delegate.getDashboardState();
        return state == null ? DashboardState.ROOT : DashboardState.valueOf(state.name());
    }

    @Override
    public List<TestSummary> listTests() {
        return delegate.listTests().stream()
                .map(ts -> new TestSummary(ts.getId(), ts.getName()))
                .collect(Collectors.toList());
    }

    @Override
    public List<ClassSummary> listClasses() {
        return delegate.listClasses().stream()
                .map(cs -> new ClassSummary(cs.getId(), cs.getName()))
                .collect(Collectors.toList());
    }

    @Override
    public ImportResult importTestFromFile(File file) {
        Server.ImportResult result = delegate.importTestFromFile(file);
        if (result == null) {
            return new ImportResult(false, "Kein Ergebnis vom Server erhalten.");
        }
        return new ImportResult(result.isSuccess(), result.getMessage());
    }

    @Override
    public void selectTestById(int id) {
        delegate.selectTestById(id);
    }

    @Override
    public String prepareHostingForClass(int classId, int durationMinutes) {
        return delegate.prepareHostingForClass(classId, durationMinutes);
    }

    @Override
    public void resetToRoot() {
        delegate.resetToRoot();
    }

    @Override
    public int getSelectedTaskCount() {
        return delegate.getSelectedTaskCount();
    }

    @Override
    public void abortHosting() {
        delegate.abortHosting();
    }

    @Override
    public void startTestRun() {
        delegate.startTestRun();
    }

    @Override
    public void pauseTestRun() {
        delegate.pauseTestRun();
    }

    @Override
    public void resumeTestRun() {
        delegate.resumeTestRun();
    }

    @Override
    public void addExtraTimeMinutes(int minutes) {
        delegate.addExtraTimeMinutes(minutes);
    }

    @Override
    public TimerStatus getTimerStatus() {
        Server.TimerStatus status = delegate.getTimerStatus();
        if (status == null) {
            return new TimerStatus(0, 0, false, false);
        }
        return new TimerStatus(
                status.getTotalSeconds(),
                status.getRemainingSeconds(),
                status.isRunning(),
                status.isPaused()
        );
    }

    @Override
    public List<ClientSummary> getClientSummaries() {
        return delegate.getClientSummaries().stream()
                .map(cs -> new ClientSummary(
                        cs.clientId(),
                        cs.studentName(),
                        cs.remoteIp(),
                        cs.status(),
                        cs.token(),
                        cs.currentTaskNumber(),
                        cs.totalTasks(),
                        cs.handRaised()
                ))
                .collect(Collectors.toList());
    }

    @Override
    public List<Warning> getActiveWarnings() {
        return delegate.getActiveWarnings().stream()
                .map(w -> new Warning(
                        w.getId(),
                        w.getClientId(),
                        w.getStudentName(),
                        w.getMessage(),
                        w.getTimestamp()
                ))
                .collect(Collectors.toList());
    }

    @Override
    public void dismissWarning(String warningId) {
        delegate.dismissWarning(warningId);
    }

    @Override
    public int getTaskCountForTest(int testId) {
        return delegate.getTaskCountForTest(testId);
    }

    @Override
    public void endRunningTest() {
        delegate.endRunningTest();
    }
}
