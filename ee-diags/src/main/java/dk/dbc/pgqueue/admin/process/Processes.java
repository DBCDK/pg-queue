package dk.dbc.pgqueue.admin.process;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.ejb.EJB;
import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Singleton;
import javax.enterprise.concurrent.ManagedExecutorService;

/**
 *
 * @author DBC {@literal <dbc.dk>}
 */
@Singleton
@Lock(LockType.READ)
public class Processes {

    @Resource(type = ManagedExecutorService.class)
    ExecutorService mes;

    @EJB
    ProcessesWebSocketBean processesState;

    private final ConcurrentHashMap<String, Process> processes = new ConcurrentHashMap<>();

    @PreDestroy
    public void destroy() {
        for (Process process : allProcesses()) {
            if (!process.isCompleted()) {
                process.cancel();
            }
        }
    }

    /**
     * Set up a process in the executor service system
     *
     * @param process process to run at first given time
     * @return identifier of job
     */
    public String registerProcess(Process process) {
        while (process.getProcessId() == null) {
            String uuid = UUID.randomUUID().toString();
            processes.computeIfAbsent(uuid, process::setProcessId);
        }
        process.setWebSocket(processesState);
        return process.getProcessId();
    }

    public Process lookup(String id) {
        return processes.get(id);
    }

    public void startProcess(String id) {
        Process process = lookup(id);
        mes.submit(process::start);
    }

    public Collection<Process> allProcesses() {
        return Collections.unmodifiableCollection(processes.values());
    }

    public void prune(String id) {
        Process process = processes.remove(id);
        if (process != null) {
            processesState.broadcastGone(process);
        }
    }
}
