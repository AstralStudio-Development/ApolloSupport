package cn.starry.apollosupport;

final class NoopRewriteService implements RewriteService {
    private final String statusMessage;

    NoopRewriteService(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    @Override
    public boolean register() {
        return false;
    }

    @Override
    public boolean isRegistered() {
        return false;
    }

    @Override
    public String getStatusMessage() {
        return statusMessage;
    }
}
