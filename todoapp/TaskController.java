package todoapp;

import org.springframework.web.bind.annotation.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import jakarta.servlet.http.HttpServletResponse;
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final ConcurrentHashMap<String, List<Task>> userTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<TaskList>> userLists = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Task>> userDeletedTasks = new ConcurrentHashMap<>();
    @GetMapping(value = "/user/{userId}", produces = "application/octet-stream")
    public void getUserTasks(@PathVariable String userId, HttpServletResponse response) throws IOException {
        List<Task> tasks = userTasks.getOrDefault(userId, new ArrayList<>());
        List<TaskList> lists = userLists.getOrDefault(userId, new ArrayList<>());
        List<Task> deletedTasks = userDeletedTasks.getOrDefault(userId, new ArrayList<>());
        assignTasksToLists(tasks, lists);
        TaskSyncData data = new TaskSyncData(tasks, deletedTasks, lists);
        response.setContentType("application/octet-stream");
        try (ObjectOutputStream oos = new ObjectOutputStream(response.getOutputStream())) {
            oos.writeObject(data);
        }
    }
    @PostMapping(value = "/sync/{userId}", consumes = "application/octet-stream")
    public void syncUserTasks(
            @PathVariable String userId,
            @RequestBody byte[] data
    ) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            TaskSyncData delta = (TaskSyncData) ois.readObject();
            // 处理已删除的任务
            List<Task> deletedTasks = userDeletedTasks.getOrDefault(userId, new ArrayList<>());
            for (Task deletedTask : delta.getDeletedTasks()) {
                System.out.println("收到已删除任务: " + deletedTask.getName() + " ID: " + deletedTask.getId());
                for (Task existingTask : deletedTasks) {
                    if (existingTask.getId().equals(deletedTask.getId())) {
                        existingTask.setName(deletedTask.getName());
                        existingTask.setCompleted(deletedTask.isCompleted());
                        existingTask.setDeleted(true);
                        existingTask.setLastModified(deletedTask.getLastModified());
                        break;
                    }
                }
            }
            userDeletedTasks.put(userId, deletedTasks);
            // 取出原有数据
            List<Task> allTasks = userTasks.getOrDefault(userId, new ArrayList<>());
            List<TaskList> allLists = userLists.getOrDefault(userId, new ArrayList<>());
            // 处理普通任务
            java.util.Map<String, Task> taskMap = new java.util.HashMap<>();
            for (Task t : allTasks) taskMap.put(t.getId(), t);
            for (Task t : delta.getTasks()) {
                System.out.println("服务端收到: " + t.getName() + " completed=" + t.isCompleted() + " lastModified=" + t.getLastModified());
                Task old = taskMap.get(t.getId());
                if (old == null || t.getLastModified() > old.getLastModified()) {
                    taskMap.put(t.getId(), t);
                }
            }
            // 处理清单
            java.util.Map<String, TaskList> listMap = new java.util.HashMap<>();
            for (TaskList l : allLists) listMap.put(l.getId(), l);
            for (TaskList l : delta.getCustomLists()) {
                TaskList old = listMap.get(l.getId());
                if (old == null || l.getLastModified() > old.getLastModified()) {
                    listMap.put(l.getId(), l);
                }
            }
            // 更新数据
            assignTasksToLists(new ArrayList<>(taskMap.values()), new ArrayList<>(listMap.values()));
            userTasks.put(userId, new ArrayList<>(taskMap.values()));
            userLists.put(userId, new ArrayList<>(listMap.values()));
            // 调试输出
            System.out.println("服务端存储的任务数: " + userTasks.get(userId).size());
            System.out.println("服务端存储的已删除任务数: " + deletedTasks.size());
            for (Task t : deletedTasks) {
                System.out.println("已删除任务: " + t.getName() + " ID: " + t.getId() + " completed=" + t.isCompleted());
            }
        }
    }
    private void assignTasksToLists(List<Task> allTasks, List<TaskList> allLists) {
        for (TaskList list : allLists) {
            list.getTasks().clear();
        }
        for (Task t : allTasks) {
            TaskList belongs = null;
            for (TaskList l : allLists) {
                if (t.getBelongsTo() != null && t.getBelongsTo().getId().equals(l.getId())) {
                    belongs = l;
                    break;
                }
            }
            if (belongs != null) {
                t.setBelongsTo(belongs);
                belongs.getTasks().add(t);
            }
        }
    }

}

