package com.example.telegramuserbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "telegram")
public class TelegramAccountProperties {

    private List<Account> accounts;
    private Long allowedCommandChatId;
    private String sharedFilesDirectory;

    public List<Account> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<Account> accounts) {
        this.accounts = accounts;
    }

    public Long getAllowedCommandChatId() {
        return allowedCommandChatId;
    }

    public void setAllowedCommandChatId(Long allowedCommandChatId) {
        this.allowedCommandChatId = allowedCommandChatId;
    }

    public String getSharedFilesDirectory() {
        return sharedFilesDirectory;
    }

    public void setSharedFilesDirectory(String sharedFilesDirectory) {
        this.sharedFilesDirectory = sharedFilesDirectory;
    }

    public static class Account {
        private String botId;
        private String name;
        private Integer apiId;
        private String apiHash;
        private String phoneNumber;
        private String sessionsDirectory;
        private String filesDirectory;

        public String getBotId() {
            return botId;
        }

        public void setBotId(String botId) {
            this.botId = botId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getApiId() {
            return apiId;
        }

        public void setApiId(Integer apiId) {
            this.apiId = apiId;
        }

        public String getApiHash() {
            return apiHash;
        }

        public void setApiHash(String apiHash) {
            this.apiHash = apiHash;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }

        public void setPhoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }

        public String getSessionsDirectory() {
            return sessionsDirectory;
        }

        public void setSessionsDirectory(String sessionsDirectory) {
            this.sessionsDirectory = sessionsDirectory;
        }

        public String getFilesDirectory() {
            return filesDirectory;
        }

        public void setFilesDirectory(String filesDirectory) {
            this.filesDirectory = filesDirectory;
        }
    }
}
