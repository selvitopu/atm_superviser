/**
 * The server's password rule, checked before sending so the user sees it at once; the server checks again. Returns
 * the broken rule, or '' when the password passes. bcrypt hashes at most 72 bytes; accented letters take two in UTF-8.
 */
export function passwordProblem(username: string, password: string): string {
  if (password.length < 10 || new TextEncoder().encode(password).length > 72) {
    return 'Passwords are 10 to 72 characters, and accented letters count double';
  }
  if (password.toLowerCase() === username.toLowerCase()) {
    return 'The password must not be the username';
  }
  return '';
}
