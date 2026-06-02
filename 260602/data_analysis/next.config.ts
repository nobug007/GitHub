import type { NextConfig } from 'next';
import path from 'path';
import os from 'os';

const linuxNodeModules = path.join(os.homedir(), 'npm_proj/data_analysis/node_modules');

const nextConfig: NextConfig = {
  webpack: (config) => {
    config.resolve.modules = [linuxNodeModules, 'node_modules'];
    return config;
  },
};

export default nextConfig;
