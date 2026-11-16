package com.georu.core.internal.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GeoruPluginRegistrar - 실행기 고르기")
class GeoruPluginRegistrarTest {

    @Test
    @DisplayName("JobLauncher 가 하나면 그것, 업체 앱이 하나 더 두어 여럿이면 jobLauncher")
    void jobLauncher() {
        DefaultListableBeanFactory one = new DefaultListableBeanFactory();
        JobLauncher only = new TaskExecutorJobLauncher();
        one.registerSingleton("customLauncher", only);
        assertThat(GeoruPluginRegistrar.jobLauncher(one)).isSameAs(only);

        DefaultListableBeanFactory two = new DefaultListableBeanFactory();
        JobLauncher boot = new TaskExecutorJobLauncher();
        two.registerSingleton("customLauncher", new TaskExecutorJobLauncher());
        two.registerSingleton("jobLauncher", boot);
        assertThat(GeoruPluginRegistrar.jobLauncher(two)).isSameAs(boot);
    }
}
