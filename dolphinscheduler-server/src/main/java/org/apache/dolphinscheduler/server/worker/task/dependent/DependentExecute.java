/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dolphinscheduler.server.worker.task.dependent;

import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.enums.DependResult;
import org.apache.dolphinscheduler.common.enums.DependentRelation;
import org.apache.dolphinscheduler.common.enums.ExecutionStatus;
import org.apache.dolphinscheduler.common.model.DateInterval;
import org.apache.dolphinscheduler.common.model.DependentItem;
import org.apache.dolphinscheduler.common.utils.DependentUtils;
import org.apache.dolphinscheduler.dao.ProcessDao;
import org.apache.dolphinscheduler.dao.entity.ProcessInstance;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.server.utils.SpringApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * dependent item execute
 */
public class DependentExecute {
    /**
     * process dao
     */
    private final ProcessDao processDao = SpringApplicationContext.getBean(ProcessDao.class);

    /**
     * depend item list
     */
    private List<DependentItem> dependItemList;

    /**
     * dependent relation
     */
    private DependentRelation relation;

    /**
     * depend result
     */
    private DependResult modelDependResult = DependResult.WAITING;

    /**
     * depend result map
     */
    private Map<String, DependResult> dependResultMap = new HashMap<>();

    /**
     * logger
     */
    private Logger logger =  LoggerFactory.getLogger(DependentExecute.class);

    /**
     * constructor
     * @param itemList  item list
     * @param relation  relation
     */
    public DependentExecute(List<DependentItem> itemList, DependentRelation relation){
        this.dependItemList = itemList;
        this.relation = relation;
    }

    /**
     * get dependent item for one dependent item
     * @param dependentItem dependent item
     * @param currentTime   current time
     * @return DependResult
     */
    public DependResult getDependentResultForItem(DependentItem dependentItem, Date currentTime){
        List<DateInterval> dateIntervals = DependentUtils.getDateIntervalList(currentTime, dependentItem.getDateValue());
        return calculateResultForTasks(dependentItem, dateIntervals );
    }

    /**
     * calculate dependent result for one dependent item.
     * @param dependentItem dependent item
     * @param dateIntervals date intervals
     * @return dateIntervals
     */
    private DependResult calculateResultForTasks(DependentItem dependentItem,
                                                        List<DateInterval> dateIntervals) {
        DependResult result = DependResult.FAILED;
        for(DateInterval dateInterval : dateIntervals){
            ProcessInstance processInstance = findLastProcessInterval(dependentItem.getDefinitionId(),
                                                    dateInterval);
            if(processInstance == null){
                logger.error("cannot find the right process instance: definition id:{}, start:{}, end:{}",
                       dependentItem.getDefinitionId(), dateInterval.getStartTime(), dateInterval.getEndTime() );
                return DependResult.FAILED;
            }
            if(dependentItem.getDepTasks().equals(Constants.DEPENDENT_ALL)){
                result = getDependResultByState(processInstance.getState());
            }else{
                TaskInstance taskInstance = null;
                List<TaskInstance> taskInstanceList = processDao.findValidTaskListByProcessId(processInstance.getId());

                for(TaskInstance task : taskInstanceList){
                    if(task.getName().equals(dependentItem.getDepTasks())){
                        taskInstance = task;
                        break;
                    }
                }
                if(taskInstance == null){
                    // cannot find task in the process instance
                    // maybe because process instance is running or failed.
                     result = getDependResultByState(processInstance.getState());
                }else{
                    result = getDependResultByState(taskInstance.getState());
                }
            }
            if(result != DependResult.SUCCESS){
                break;
            }
        }
        return result;
    }

    /**
     * find the last one process instance that :
     * 1. manual run and finish between the interval
     * 2. schedule run and schedule time between the interval
     * @param definitionId  definition id
     * @param dateInterval  date interval
     * @return ProcessInstance
     */
    private ProcessInstance findLastProcessInterval(int definitionId, DateInterval dateInterval) {

        ProcessInstance runningProcess = processDao.findLastRunningProcess(definitionId, dateInterval);
        if(runningProcess != null){
            return runningProcess;
        }

        ProcessInstance lastSchedulerProcess = processDao.findLastSchedulerProcessInterval(
                definitionId, dateInterval
        );

        ProcessInstance lastManualProcess = processDao.findLastManualProcessInterval(
                definitionId, dateInterval
        );

        if(lastManualProcess ==null){
            return lastSchedulerProcess;
        }
        if(lastSchedulerProcess == null){
            return lastManualProcess;
        }

        return (lastManualProcess.getEndTime().after(lastSchedulerProcess.getEndTime()))?
                lastManualProcess : lastSchedulerProcess;
    }

    /**
     * get dependent result by task/process instance state
     * @param state state
     * @return DependResult
     */
    private DependResult getDependResultByState(ExecutionStatus state) {

        if(state.typeIsRunning() || state == ExecutionStatus.SUBMITTED_SUCCESS || state == ExecutionStatus.WAITTING_THREAD){
            return DependResult.WAITING;
        }else if(state.typeIsSuccess()){
            return DependResult.SUCCESS;
        }else{
            return DependResult.FAILED;
        }
    }

    /**
     * judge depend item finished
     * @param currentTime current time
     * @return boolean
     */
    public boolean finish(Date currentTime){
        if(modelDependResult == DependResult.WAITING){
            modelDependResult = getModelDependResult(currentTime);
            return false;
        }
        return true;
    }

    /**
     * get model depend result
     * @param currentTime current time
     * @return DependResult
     */
    public DependResult getModelDependResult(Date currentTime){

        List<DependResult> dependResultList = new ArrayList<>();

        for(DependentItem dependentItem : dependItemList){
            DependResult dependResult = getDependResultForItem(dependentItem, currentTime);
            if(dependResult != DependResult.WAITING){
                dependResultMap.put(dependentItem.getKey(), dependResult);
            }
            dependResultList.add(dependResult);
        }
        modelDependResult = DependentUtils.getDependResultForRelation(
                this.relation, dependResultList
        );
        return modelDependResult;
    }

    /**
     * get dependent item result
     * @param item          item
     * @param currentTime   current time
     * @return DependResult
     */
    public DependResult getDependResultForItem(DependentItem item, Date currentTime){
        String key = item.getKey();
        if(dependResultMap.containsKey(key)){
            return dependResultMap.get(key);
        }
        return getDependentResultForItem(item, currentTime);
    }

    public Map<String, DependResult> getDependResultMap(){
        return dependResultMap;
    }

}
