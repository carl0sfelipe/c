
#include <stdio.h>
#include <pthread.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>

int counter = 0;
pthread_mutex_t counter_mutex = PTHREAD_MUTEX_INITIALIZER;
int running = 1;

int pause_increment[10] = {0};
int pause_decrement[10] = {0};
pthread_t threads_inc[10], threads_dec[10];
int num_threads_inc = 0, num_threads_dec = 0;

void *increment(void *arg) {
    int idx = *(int *)arg - 1;
    while (running) {
        while (pause_increment[idx]) {
            sleep(1);
        }
        pthread_mutex_lock(&counter_mutex);
        counter++;
        pthread_mutex_unlock(&counter_mutex);
        sleep(1);
    }
    free(arg);
    return NULL;
}

void *decrement(void *arg) {
    int idx = *(int *)arg - 1;
    while (running) {
        while (pause_decrement[idx]) {
            sleep(1);
        }
        pthread_mutex_lock(&counter_mutex);
        counter--;
        pthread_mutex_unlock(&counter_mutex);
        sleep(1);
    }
    free(arg);
    return NULL;
}

int main() {
    char command[50];

    while (1) {
        printf("\nCommands:\n");
        printf("ci - Create incrementing thread\n");
        printf("cd - Create decrementing thread\n");
        printf("pi[n] - Pause incrementing thread n\n");
        printf("pd[n] - Pause decrementing thread n\n");
        printf("ci[n] - Continue incrementing thread n\n");
        printf("cd[n] - Continue decrementing thread n\n");
        printf("s - Show counter status\n");
        printf("e - Exit\n");
        printf("Enter a command: ");
        fgets(command, 50, stdin);
        strtok(command, "\n");

        if (strncmp(command, "ci", 2) == 0 && strlen(command) == 2) {
            if (num_threads_inc < 10) {
                int *idx = malloc(sizeof(int));
                *idx = num_threads_inc + 1;
                pthread_create(&threads_inc[num_threads_inc], NULL, increment, idx);
                num_threads_inc++;
                printf("Incrementing thread %d created successfully!\n", *idx);
            } else {
                printf("Maximum incrementing threads reached!\n");
            }
        } else if (strncmp(command, "cd", 2) == 0 && strlen(command) == 2) {
            if (num_threads_dec < 10) {
                int *idx = malloc(sizeof(int));
                *idx = num_threads_dec + 1;
                pthread_create(&threads_dec[num_threads_dec], NULL, decrement, idx);
                num_threads_dec++;
                printf("Decrementing thread %d created successfully!\n", *idx);
            } else {
                printf("Maximum decrementing threads reached!\n");
            }
        } else if (strncmp(command, "pi", 2) == 0) {
            int idx = atoi(&command[2]) - 1;
            if (idx >= 0 && idx < num_threads_inc) {
                pause_increment[idx] = 1;
                printf("Incrementing thread %d paused successfully!\n", idx + 1);
            } else {
                printf("Invalid index!\n");
            }
        } else if (strncmp(command, "pd", 2) == 0) {
            int idx = atoi(&command[2]) - 1;
            if (idx >= 0 && idx < num_threads_dec) {
                pause_decrement[idx] = 1;
                printf("Decrementing thread %d paused successfully!\n", idx + 1);
            } else {
                printf("Invalid index!\n");
            }
        } else if (strncmp(command, "ci", 2) == 0) {
            int idx = atoi(&command[2]) - 1;
            if (idx >= 0 && idx < num_threads_inc) {
                pause_increment[idx] = 0;
                printf("Incrementing thread %d continued successfully!\n", idx + 1);
            } else {
                printf("Invalid index!\n");
            }
        } else if (strncmp(command, "cd", 2) == 0) {
            int idx = atoi(&command[2]) - 1;
            if (idx >= 0 && idx < num_threads_dec) {
                pause_decrement[idx] = 0;
                printf("Decrementing thread %d continued successfully!\n", idx + 1);
            } else {
                printf("Invalid index!\n");
            }
        } else if (strcmp(command, "s") == 0) {
            pthread_mutex_lock(&counter_mutex);
            printf("Counter: %d\n", counter);
            printf("Created incrementing threads: %d\n", num_threads_inc);
            printf("Created decrementing threads: %d\n", num_threads_dec);
            pthread_mutex_unlock(&counter_mutex);
        } else if (strcmp(command, "e") == 0) {
            running = 0;
            for (int i = 0; i < num_threads_inc; i++) {
                pthread_join(threads_inc[i], NULL);
            }
            for (int i = 0; i < num_threads_dec; i++) {
                pthread_join(threads_dec[i], NULL);
            }
            pthread_mutex_destroy(&counter_mutex);
            exit(0);
        } else {
            printf("Unknown command!\n");
        }
    }

    return 0;
}
