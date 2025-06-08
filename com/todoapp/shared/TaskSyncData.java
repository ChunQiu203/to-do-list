package com.todoapp.shared;

import java.io.Serializable;
import java.util.List;
public class TaskSyncData implements Serializable {
    private List<Task> tasks;
    private List<TaskList> customLists;
    private List<Task> deletedTasks;

    public TaskSyncData(List<Task> tasks, List<Task> deletedTasks, List<TaskList> customLists) {
        this.tasks = tasks;
        this.deletedTasks = deletedTasks;
        this.customLists = customLists;
    }

    public List<Task> getTasks() { return tasks; }
    public List<TaskList> getCustomLists() { return customLists; }
    public List<Task> getDeletedTasks() { return deletedTasks; }
}