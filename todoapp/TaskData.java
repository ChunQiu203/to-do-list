package todoapp;
import java.io.Serializable;
import javafx.collections.ObservableList;
public class TaskData implements Serializable {
    private static final long serialVersionUID = 1L;
    // 存储任务和清单的基本数据（非 ObservableList）
    private Task[] uncompletedTasks;
    private Task[] completedTasks;
    private TaskList[] customLists;
    // 构造函数（接收 ObservableList 并转换为数组）
    public TaskData(ObservableList<Task> uncompleted,
                    ObservableList<Task> completed,
                    ObservableList<TaskList> lists) {
        this.uncompletedTasks = uncompleted.toArray(new Task[0]);
        this.completedTasks = completed.toArray(new Task[0]);
        this.customLists = lists.toArray(new TaskList[0]);
    }
    // 反序列化时恢复 ObservableList
    public void populateObservableLists(
            ObservableList<Task> uncompleted,
            ObservableList<Task> completed,
            ObservableList<TaskList> lists, MainBorderPane mainApp) {
        for (Task task : uncompletedTasks) {
            task.restoreListener(mainApp); // 恢复监听器
            uncompleted.add(task);
        }
        for (Task task : completedTasks) {
            task.restoreListener(mainApp); // 恢复监听器
            completed.add(task);
        }
        for (TaskList list : customLists) {
            list.restoreFX(); // 恢复清单的FX属性
            // 恢复清单中所有任务的监听器
            for (Task task : list.getTasks()) {
                task.restoreListener(mainApp);
            }
            // 检查是否存在同名清单
            boolean found = false;
            for (int i = 0; i < lists.size(); i++) {
                TaskList existingList = lists.get(i);
                if (existingList.getName().equals(list.getName())) {
                    // 如果新清单的修改时间更晚，则替换旧清单
                    if (list.getLastModified() > existingList.getLastModified()) {
                        lists.set(i, list);
                        System.out.println("替换同名清单: " + list.getName());
                    }
                    lists.set(i, list);
                    System.out.println("替换同名清单: " + list.getName());
                    found = true;
                    break;
                }
            }
            // 如果没找到同名清单，则添加新清单
            if (!found) {
                lists.add(list);
                System.out.println("添加新清单: " + list.getName());
            }
        }
    }
}