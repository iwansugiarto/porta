// Notification click handler — focus/navigate to the right conversation
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const data = event.notification.data || {};
  const urlPath = data.url || '/';

  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      // Try to focus an existing window
      for (const client of clientList) {
        if (client.url.includes(self.location.origin) && 'focus' in client) {
          client.focus();
          if (data.cascadeId) {
            client.postMessage({ type: 'NOTIFICATION_CLICK', cascadeId: data.cascadeId });
          }
          return;
        }
      }
      // If no window found, open a new one
      return clients.openWindow(urlPath);
    })
  );
});
