function handler(event) {
  var request = event.request;
  // Defensive guard; /api and /api/* are handled by separate behaviors.
  if (request.uri === '/api' || request.uri.indexOf('/api/') === 0) return request;
  var lastSegment = request.uri.substring(request.uri.lastIndexOf('/') + 1);
  if (lastSegment.indexOf('.') === -1) request.uri = '/index.html';
  return request;
}
