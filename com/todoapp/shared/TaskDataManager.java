package com.todoapp.shared;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import javafx.application.Platform;

public class TaskDataManager {
    private MainBorderPane mainApp;
    private final String DATA_FILE;
    public TaskDataManager(MainBorderPane app, String userId) {
        this.mainApp = app;
        String userHome = System.getProperty("user.home");
        this.DATA_FILE = userHome + File.separator + "todo_data_" + userId + ".ser";
    }
    // 保存数据时，自动处理已删除清单的任务引用
    public void saveData() {
        saveData(false);
    }
    // 添加一个重载方法，允许静默保存
    public void saveData(boolean silent) {
        try {
            // 确保目录存在
            File file = new File(DATA_FILE);
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new FileOutputStream(DATA_FILE))) {
                // 过滤掉已删除的清单（如果有标记）
                TaskData data = new TaskData(
                        mainApp.uncompletedTasks,
                        mainApp.completedTasks,
                        mainApp.customLists
                );
                oos.writeObject(data);
                System.out.println("数据已保存到: " + DATA_FILE);
                System.out.println("保存的任务数: " + (mainApp.uncompletedTasks.size() + mainApp.completedTasks.size()));
            }
        } catch (IOException e) {
            System.err.println("保存数据失败: " + e.getMessage());
            e.printStackTrace();
            if (!silent) {
                mainApp.showAlert("保存失败", "无法保存数据: " + e.getMessage());
            }
        }
    }
    public void loadData() {
        File file = new File(DATA_FILE);
        System.out.println("尝试加载数据文件: " + DATA_FILE);
        if (!file.exists()) {
            System.out.println("数据文件不存在，将使用新数据");
            return;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(DATA_FILE))) {
            System.out.println("开始读取数据文件...");
            TaskData data = (TaskData) ois.readObject();
            System.out.println("数据文件读取成功，开始恢复数据...");
            data.populateObservableLists(
                    mainApp.uncompletedTasks,
                    mainApp.completedTasks,
                    mainApp.customLists,
                    mainApp
            );
            // 使用Platform.runLater在UI线程中更新界面
            Platform.runLater(() -> {
                mainApp.refreshCurrentView(); // 刷新当前任务视图
                mainApp.refreshCustomListsUI(); // 刷新清单导航按钮
                System.out.println("数据加载完成，未完成任务数: " + mainApp.uncompletedTasks.size() + 
                                 ", 已完成任务数: " + mainApp.completedTasks.size() + 
                                 ", 自定义清单数: " + mainApp.customLists.size());
            });
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("加载数据失败: " + e.getMessage());
            e.printStackTrace();
            mainApp.showAlert("加载失败", "无法加载数据: " + e.getMessage());
        }
    }
    // 合并远程数据并保存
    public void mergeAndSave(TaskSyncData remoteData) {
        System.out.println("mergeAndSave remoteData: " + remoteData.getTasks());
        // 如果远程数据为空，不进行合并
        if (remoteData == null || remoteData.getTasks().isEmpty()) {
            System.out.println("远程数据为空，跳过合并");
            return;
        }

        // 处理已删除的任务
        if (remoteData.getDeletedTasks() != null) {
            for (Task deletedTask : remoteData.getDeletedTasks()) {
                // 从所有列表中移除已删除的任务
                mainApp.uncompletedTasks.removeIf(task -> task.getId().equals(deletedTask.getId()));
                mainApp.completedTasks.removeIf(task -> task.getId().equals(deletedTask.getId()));
                // 从自定义清单中移除
                for (TaskList list : mainApp.customLists) {
                    list.getTasks().removeIf(task -> task.getId().equals(deletedTask.getId()));
                }
                // 添加到已删除任务列表
                if (!mainApp.deletedTasks.contains(deletedTask)) {
                    mainApp.deletedTasks.add(deletedTask);
                }
            }
        }

        // 合并全局任务
        Map<String, Task> localGlobalMap = new HashMap<>();
        for (Task t : mainApp.uncompletedTasks) localGlobalMap.put(t.getId(), t);
        for (Task t : mainApp.completedTasks) localGlobalMap.put(t.getId(), t);
        Map<String, Task> remoteGlobalMap = new HashMap<>();
        for (Task t : remoteData.getTasks()) remoteGlobalMap.put(t.getId(), t);

        // 合并远程任务
        for (Task remote : remoteData.getTasks()) {
            Task local = localGlobalMap.get(remote.getId());
            remote.restoreFX();
            if (local == null) {
                if (!remote.isDeleted()) {
                    if (remote.isCompleted()) mainApp.completedTasks.add(remote);
                    else mainApp.uncompletedTasks.add(remote);
                }
            } else {
                if (remote.getLastModified() > local.getLastModified()) {
                    local.setName(remote.getName());
                    local.completed = remote.isCompleted();
                    local.setDeleted(remote.isDeleted());
                    local.setLastModified(remote.getLastModified());
                }
            }
        }

        // 清理已删除的任务
        mainApp.uncompletedTasks.removeIf(Task::isDeleted);
        mainApp.completedTasks.removeIf(Task::isDeleted);
        Platform.runLater(() -> mainApp.refreshCurrentView());
        System.out.println("mergeAndSave 本地uncompletedTasks: " + mainApp.uncompletedTasks.size());
        System.out.println("mergeAndSave 本地completedTasks: " + mainApp.completedTasks.size());
        System.out.println("mergeAndSave 本地deletedTasks: " + mainApp.deletedTasks.size());

        // 合并自定义清单及其任务
        Map<String, TaskList> localListMap = new HashMap<>();
        for (TaskList l : mainApp.customLists) localListMap.put(l.getId(), l);

        // 合并远程清单
        for (TaskList remoteList : remoteData.getCustomLists()) {
            remoteList.restoreFX();
            TaskList localList = localListMap.get(remoteList.getId());
            if (localList == null) {
                mainApp.customLists.add(remoteList);
                for (Task t : remoteList.getTasks()) t.setBelongsTo(remoteList);
            } else {
                // 合并清单下的任务
                Map<String, Task> localTaskMap = new HashMap<>();
                for (Task t : localList.getTasks()) localTaskMap.put(t.getId(), t);
                
                for (Task remoteTask : remoteList.getTasks()) {
                    remoteTask.restoreFX();
                    Task localTask = localTaskMap.get(remoteTask.getId());
                    if (localTask == null) {
                        if (!remoteTask.isDeleted()) {
                            remoteTask.setBelongsTo(localList);
                            localList.getTasks().add(remoteTask);
                        }
                    } else {
                        if (remoteTask.getLastModified() > localTask.getLastModified()) {
                            localTask.setName(remoteTask.getName());
                            localTask.setCompleted(remoteTask.isCompleted());
                            localTask.setDeleted(remoteTask.isDeleted());
                            localTask.setLastModified(remoteTask.getLastModified());
                        }
                        localTask.setBelongsTo(localList);
                    }
                }
                localList.getTasks().removeIf(Task::isDeleted);
            }
        }
        mainApp.customLists.removeIf(TaskList::isDeleted);
        saveData(true);
    }
}