/**
 * Utility functions for HTML5 Web Notifications API.
 */

/**
 * Check if the browser supports standard HTML5 Web Notifications.
 */
export function isNotificationsSupported(): boolean {
  return typeof window !== "undefined" && "Notification" in window;
}

/**
 * Get current browser notification permission status.
 */
export function getNotificationPermission(): NotificationPermission {
  if (!isNotificationsSupported()) return "default";
  return Notification.permission;
}

/**
 * Request notification permission from the user.
 * Must be triggered by a direct user gesture (e.g. click).
 */
export async function requestNotificationPermission(): Promise<NotificationPermission> {
  if (!isNotificationsSupported()) return "default";
  try {
    return await Notification.requestPermission();
  } catch (err) {
    console.error("Failed to request notification permission:", err);
    return Notification.permission;
  }
}

/**
 * Trigger a system notification banner.
 *
 * Prefers `ServiceWorkerRegistration.showNotification()` which works reliably
 * when the tab is backgrounded on mobile PWAs. Falls back to `new Notification()`
 * if no active service worker is available.
 */
export async function triggerWebNotification(
  title: string,
  options?: NotificationOptions & { data?: Record<string, unknown> }
): Promise<void> {
  if (!isNotificationsSupported() || Notification.permission !== "granted") {
    return;
  }

  const mergedOptions: NotificationOptions & { data?: Record<string, unknown> } = {
    icon: "/icons/icon-192.png",
    badge: "/icons/icon-192.png",
    ...options,
  };

  try {
    // Prefer service worker showNotification — works when tab is backgrounded
    if ("serviceWorker" in navigator) {
      const reg = await navigator.serviceWorker.ready;
      await reg.showNotification(title, mergedOptions);
      return;
    }
  } catch (err) {
    console.warn("SW showNotification failed, falling back to Notification():", err);
  }

  // Fallback: standard Notification constructor
  try {
    const notification = new Notification(title, mergedOptions);

    notification.onclick = () => {
      window.focus();
      notification.close();
    };
  } catch (err) {
    console.error("Failed to trigger web notification:", err);
  }
}
