import { post } from '../request'

export const authApi = {
  login: (payload: { account: string; password: string; loginType: string }) =>
    post('/api/auth/Login', payload),
  smsSend: (payload: { phone: string }) => post('/api/auth/sms/send', payload),
  smsLogin: (payload: { phone: string; code: string }) => post('/api/auth/sms/login', payload),
  register: (payload: { username: string; password: string; confirmPassword: string; nickname: string; phone: string }) =>
    post('/api/auth/register', payload),
}
